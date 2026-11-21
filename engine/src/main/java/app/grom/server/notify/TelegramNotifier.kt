// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.net.HttpJsonClient
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Delivers the operator's alerts to their own chat (D65, D66).
 *
 * This is one of the only two places in Grom where a host is compiled in (the
 * other is the update feed, D37). The Bot API is a single fixed host, so it
 * cannot be a runtime-supplied URL the way the Lightning backend is; the
 * compensation is that the feature is off unless the operator enables it, the
 * credential is entered at runtime, and the body is a fixed sentence.
 *
 * Bounded on purpose (D66):
 *   - one daemon worker thread, a 16-message queue that drops the oldest;
 *   - the same event within [COALESCE_MS] collapses into one message;
 *   - at most [MAX_PER_MINUTE] messages a minute;
 *   - at most two attempts per message, the second after [RETRY_DELAY_MS];
 *   - no repeating task, no waking tick, no persisted queue.
 *
 * A failure is recorded as a failure with a stable code. The URL carries the bot
 * token, so nothing derived from an exception message may reach the caller: only
 * codes do.
 */
class TelegramNotifier(
    private val store: NotifySettingsStore,
    private val transport: JsonTransport = HttpJsonClient(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = {},
    /** Injectable so a test does not have to sleep for the real 4 s. */
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val maxPerMinute: Int = MAX_PER_MINUTE
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayDeque<NotifyMessage>()
    private val lastQueuedAt = HashMap<String, Long>()
    private val sentAt = ArrayDeque<Long>()

    private var inFlight = false
    private var closed = false
    private var dropped = 0
    private var coalesced = 0
    private var throttled = 0

    @Volatile
    private var lastDelivery: Map<String, Any?>? = null

    private val worker = Thread({ loop() }, "grom-notify").apply {
        isDaemon = true
        start()
    }

    /** The sanitised block for status and export (D67). */
    fun publicStatus(): Map<String, Any?> = NotifyStatus.build(store.settings(), lastDelivery)

    /** The result of the last attempt, or null before the first one. */
    fun lastDelivery(): Map<String, Any?>? = lastDelivery

    /**
     * The audit funnel calls this for every recorded action. It never throws and
     * never blocks: an audit append must not fail because a notification feature
     * is misconfigured.
     */
    fun onAudit(action: String, detail: String) {
        try {
            val message = NotifyRules.classify(action, detail) ?: return
            val settings = store.settings()
            if (!settings.enabled || !settings.isConfigured()) return
            enqueue(message)
        } catch (_: Throwable) {
            // A notification problem never propagates into the audit path.
            logger("notification skipped")
        }
    }

    /**
     * Sends one message right now, bypassing the queue and the rate limit: the
     * operator asked for it in this moment. Still bounded to two attempts, and
     * still required to see `{"ok":true}` before reporting success.
     */
    fun sendTest(): Map<String, Any?> {
        val settings = store.settings()
        if (!settings.isConfigured()) {
            return mapOf(
                "ok" to false,
                "code" to "NOTIFY_NOT_CONFIGURED",
                "message" to "enter a bot token and a chat id first"
            )
        }
        return deliver(NotifyMessage(NotifyEvent.TEST), settings)
    }

    /** Waits until the queue is empty and nothing is in flight, or the timeout. */
    fun flush(timeoutMs: Long) {
        val deadline = clock() + timeoutMs
        lock.withLock {
            while (queue.isNotEmpty() || inFlight) {
                val remaining = deadline - clock()
                if (remaining <= 0) return
                changed.await(minOf(remaining, 100L), TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Test/diagnostic counters; never published in status. */
    fun counters(): Map<String, Int> = lock.withLock {
        mapOf("dropped" to dropped, "coalesced" to coalesced, "throttled" to throttled)
    }

    override fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
        runCatching { worker.join(STOP_JOIN_MS) }
    }

    // ------------------------------------------------------------------ worker

    @Suppress("ControlFlowWithEmptyBody")
    private fun loop() {
        while (true) {
            val message = lock.withLock {
                while (queue.isEmpty() && !closed) {
                    changed.await(POLL_MS, TimeUnit.MILLISECONDS)
                }
                if (closed && queue.isEmpty()) return
                inFlight = true
                queue.removeFirst()
            }
            try {
                if (withinRate()) {
                    deliver(message, store.settings())
                } else {
                    lock.withLock { throttled += 1 }
                }
            } catch (_: Throwable) {
                logger("notification delivery failed")
            } finally {
                lock.withLock {
                    inFlight = false
                    changed.signalAll()
                }
            }
        }
    }

    private fun enqueue(message: NotifyMessage) {
        lock.withLock {
            val now = clock()
            val previous = lastQueuedAt[message.event.code]
            if (previous != null && now - previous < COALESCE_MS) {
                coalesced += 1
                return
            }
            lastQueuedAt[message.event.code] = now
            if (queue.size >= MAX_QUEUE) {
                queue.removeFirst()
                dropped += 1
            }
            queue.addLast(message)
            changed.signalAll()
        }
    }

    /** Sliding one-minute window over the messages actually attempted. */
    private fun withinRate(): Boolean {
        lock.withLock {
            val now = clock()
            while (sentAt.isNotEmpty() && now - sentAt.first() > RATE_WINDOW_MS) {
                sentAt.removeFirst()
            }
            if (sentAt.size >= maxPerMinute) return false
            sentAt.addLast(now)
            return true
        }
    }

    // -------------------------------------------------------------- delivery

    private fun deliver(message: NotifyMessage, settings: NotifySettings): Map<String, Any?> {
        val token = store.openToken()
        if (token == null) {
            lastDelivery = linkedMapOf<String, Any?>("ok" to false, "atMs" to clock())
            return mapOf("ok" to false, "code" to "NOTIFY_NOT_CONFIGURED")
        }

        var outcome = attempt(token, settings.chatId, message.text())
        if (!outcome.ok && outcome.retryable) {
            runCatching { Thread.sleep(retryDelayMs) }
            outcome = attempt(token, settings.chatId, message.text())
        }

        lastDelivery = linkedMapOf<String, Any?>("ok" to outcome.ok, "atMs" to clock())
        logger(if (outcome.ok) "notification delivered" else "notification failed: ${outcome.code}")
        return mapOf("ok" to outcome.ok, "code" to outcome.code)
    }

    private data class Outcome(val ok: Boolean, val code: String?, val retryable: Boolean)

    /**
     * One HTTPS round trip. The body is a fixed sentence plus the numeric chat
     * id; the URL is the only place the token appears and it never leaves this
     * method — [TransportException] carries a constant message by construction.
     */
    private fun attempt(token: String, chatId: String, text: String): Outcome {
        val url = "$API_BASE/bot$token/sendMessage"
        val body = Json.encode(
            linkedMapOf(
                "chat_id" to chatId,
                "text" to text,
                "disable_web_page_preview" to true
            )
        )
        val response = try {
            transport.request(
                method = "POST",
                url = url,
                headers = mapOf("Content-Type" to "application/json"),
                body = body,
                timeoutMs = TIMEOUT_MS
            )
        } catch (_: TransportException) {
            return Outcome(ok = false, code = "TELEGRAM_TRANSPORT", retryable = true)
        } catch (_: Throwable) {
            return Outcome(ok = false, code = "TELEGRAM_FAILED", retryable = false)
        }

        if (response.status in 500..599) {
            return Outcome(ok = false, code = "TELEGRAM_HTTP_${response.status}", retryable = true)
        }
        val parsed = JsonParser.parseObjectOrNull(response.body)
        if (parsed?.get("ok") == true) {
            return Outcome(ok = true, code = null, retryable = false)
        }
        val providerCode = (parsed?.get("error_code") as? Number)?.toInt()
        val code = when {
            providerCode != null -> "TELEGRAM_HTTP_$providerCode"
            response.status in 200..299 -> "TELEGRAM_REJECTED"
            else -> "TELEGRAM_HTTP_${response.status}"
        }
        return Outcome(ok = false, code = code, retryable = false)
    }

    companion object {

        /** The second, and last, compiled host (D65). */
        const val API_BASE: String = "https://api.telegram.org"

        const val MAX_QUEUE: Int = 16
        const val MAX_PER_MINUTE: Int = 20
        const val RATE_WINDOW_MS: Long = 60_000
        const val COALESCE_MS: Long = 10_000
        const val RETRY_DELAY_MS: Long = 4_000
        const val TIMEOUT_MS: Int = 8_000

        private const val POLL_MS = 1_000L
        private const val STOP_JOIN_MS = 1_000L
    }
}
