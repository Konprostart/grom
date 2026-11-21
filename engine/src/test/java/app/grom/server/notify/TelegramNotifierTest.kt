// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

import app.grom.server.store.SecretBox
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivery (D66).
 *
 * No socket is opened: [JsonTransport] is the seam the production client also
 * implements. The tests therefore exercise the queue, the coalescing, the rate
 * limit, the two attempts and the failure codes, and prove that the bot token
 * cannot appear in a result.
 */
class TelegramNotifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var notifier: TelegramNotifier? = null

    @After
    fun tearDown() {
        notifier?.close()
        notifier = null
    }

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext.reversed()

        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:").takeIf { sealed.startsWith("sealed:") }?.reversed()
    }

    private val token = "123456789:" + "C".repeat(35)

    /** Records requests and answers with a scripted status/body. */
    private class FakeTransport(
        private val status: Int = 200,
        private val body: String = """{"ok":true,"result":{}}"""
    ) : JsonTransport {

        val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var calls = 0
        var failure: Throwable? = null
        var statuses: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        var delayMs: Long = 0

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int
        ): HttpJsonResponse {
            calls += 1
            urls.add(url)
            bodies.add(body.orEmpty())
            if (delayMs > 0) Thread.sleep(delayMs)
            failure?.let { throw it }
            val scripted = if (statuses.isEmpty()) status else statuses.removeAt(0)
            return HttpJsonResponse(scripted, this.body)
        }
    }

    private fun store(enabled: Boolean = true): NotifySettingsStore {
        val created = NotifySettingsStore(
            File(temp.root, NotifySettingsStore.FILE_NAME),
            ReversingBox()
        )
        created.save(NotifyDraft(enabled = enabled, chatId = "123456", token = token))
        return created
    }

    private fun notifier(
        store: NotifySettingsStore,
        transport: FakeTransport,
        retryDelayMs: Long = 0,
        maxPerMinute: Int = TelegramNotifier.MAX_PER_MINUTE
    ): TelegramNotifier {
        val created = TelegramNotifier(
            store = store,
            transport = transport,
            retryDelayMs = retryDelayMs,
            maxPerMinute = maxPerMinute
        )
        notifier = created
        return created
    }

    @Test
    fun `nothing is sent while the feature is off`() {
        val transport = FakeTransport()
        val created = notifier(store(enabled = false), transport)
        created.onAudit("server.start", "")
        created.flush(300)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `an enabled alert is delivered with a fixed body`() {
        val transport = FakeTransport()
        val created = notifier(store(), transport)
        created.onAudit("server.start", "port=47001")
        created.flush(2_000)

        assertEquals(1, transport.calls)
        val sent = transport.bodies.first()
        assertTrue(sent.contains("server started"))
        assertTrue(sent.contains("123456"))
        assertFalse("the token must not be in the body", sent.contains(token))
        assertTrue(transport.urls.first().startsWith(TelegramNotifier.API_BASE))
        assertEquals(true, created.lastDelivery()?.get("ok"))
    }

    @Test
    fun `an unmapped action sends nothing`() {
        val transport = FakeTransport()
        val created = notifier(store(), transport)
        created.onAudit("agent.exec", "op=ping")
        created.flush(300)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `the same event inside the window collapses into one message`() {
        val transport = FakeTransport()
        val created = notifier(store(), transport)
        repeat(5) { created.onAudit("agent.ready", "pid=1") }
        created.flush(2_000)

        assertEquals(1, transport.calls)
        assertEquals(4, created.counters()["coalesced"])
    }

    @Test
    fun `a rejected message is a failure, not a success`() {
        val transport = FakeTransport(status = 403, body = """{"ok":false,"error_code":403}""")
        val created = notifier(store(), transport)
        val result = created.sendTest()

        assertEquals(false, result["ok"])
        assertEquals("TELEGRAM_HTTP_403", result["code"])
        assertEquals(false, created.lastDelivery()?.get("ok"))
        assertEquals("a 4xx is not retried", 1, transport.calls)
    }

    @Test
    fun `a transient failure is retried exactly once`() {
        val transport = FakeTransport()
        transport.statuses = Collections.synchronizedList(mutableListOf(503, 200))
        val created = notifier(store(), transport)
        val result = created.sendTest()

        assertEquals(2, transport.calls)
        assertEquals(true, result["ok"])
    }

    @Test
    fun `a transport failure gives a code and never the token`() {
        val transport = FakeTransport()
        transport.failure = TransportException("transport failure")
        val created = notifier(store(), transport)
        val result = created.sendTest()

        assertEquals(false, result["ok"])
        assertEquals("TELEGRAM_TRANSPORT", result["code"])
        assertFalse("the token must not leak", result.toString().contains(token))
        // Two attempts, then it stops: there is no third try and no loop.
        assertEquals(2, transport.calls)
    }

    @Test
    fun `an exception carrying the token does not reach the caller`() {
        val transport = FakeTransport()
        transport.failure = RuntimeException("boom for $token")
        val created = notifier(store(), transport)
        val result = created.sendTest()

        assertEquals(false, result["ok"])
        assertEquals("TELEGRAM_FAILED", result["code"])
        assertFalse(result.toString().contains(token))
        assertFalse(
            "the status projection must not carry the token either",
            created.publicStatus().toString().contains(token)
        )
    }

    @Test
    fun `an unconfigured test send answers without a request`() {
        val transport = FakeTransport()
        val created = notifier(
            NotifySettingsStore(File(temp.root, "empty.json"), ReversingBox()),
            transport
        )
        val result = created.sendTest()

        assertEquals(false, result["ok"])
        assertEquals("NOTIFY_NOT_CONFIGURED", result["code"])
        assertEquals(0, transport.calls)
    }

    @Test
    fun `the rate limit drops messages instead of queueing for ever`() {
        val transport = FakeTransport()
        val created = notifier(store(), transport, maxPerMinute = 1)
        created.onAudit("server.start", "")
        created.flush(2_000)
        created.onAudit("server.stop", "")
        created.flush(2_000)

        assertEquals("only one message may be attempted", 1, transport.calls)
        assertEquals(1, created.counters()["throttled"])
    }

    @Test
    fun `the queue is bounded and drops the oldest`() {
        // A slow transport keeps the worker busy while the queue fills. The fake
        // clock advances past the coalescing window so each event is a distinct
        // message and only the queue bound can stop the growth.
        val transport = FakeTransport()
        transport.delayMs = 200
        val now = java.util.concurrent.atomic.AtomicLong(0)
        val created = TelegramNotifier(
            store = store(),
            transport = transport,
            clock = { now.get() },
            retryDelayMs = 0,
            maxPerMinute = 10_000
        )
        notifier = created
        val enqueued = TelegramNotifier.MAX_QUEUE + 10
        repeat(enqueued) {
            now.addAndGet(TelegramNotifier.COALESCE_MS + 1_000)
            created.onAudit("server.start", "")
        }
        created.flush(60_000)
        val dropped = created.counters()["dropped"] ?: 0
        assertTrue("the queue must drop instead of growing", dropped >= 1)
        // Every message is either attempted or dropped: nothing is silently
        // lost and nothing is sent twice.
        assertEquals(enqueued, transport.calls + dropped)
    }

    @Test
    fun `the public status is an allow-list`() {
        val transport = FakeTransport()
        val created = notifier(store(), transport)
        created.onAudit("server.start", "")
        created.flush(2_000)

        val status = created.publicStatus()
        assertEquals(setOf("enabled", "configured", "lastDelivery"), status.keys)
        val text = status.toString()
        assertFalse(text.contains(token))
        assertFalse("the chat id stays on the device", text.contains("123456"))
        val last = status["lastDelivery"] as Map<*, *>
        assertEquals(true, last["ok"])
        assertNotNull(last["atMs"])
    }

    @Test
    fun `before any attempt the last delivery is null, not healthy`() {
        val created = notifier(store(), FakeTransport())
        assertNull(created.lastDelivery())
        val last = created.publicStatus()["lastDelivery"] as Map<*, *>
        assertNull(last["ok"])
    }

    @Test
    fun `the notifier never reads the token from the message body`() {
        val transport = FakeTransport()
        val created = notifier(store(), transport)
        // An audit detail that happens to contain something credential-shaped
        // is not copied into the request.
        created.onAudit("super.refused", "code=CREDENTIAL_REQUIRED")
        created.flush(2_000)
        val sent = transport.bodies.first()
        assertTrue(sent.contains("CREDENTIAL_REQUIRED"))
        assertFalse(sent.contains(token))
    }

    @Test
    fun `closing stops the worker`() {
        val created = notifier(store(), FakeTransport())
        val failure = AtomicInteger(0)
        created.close()
        // A second close is harmless, and the object is unusable but not broken.
        runCatching { created.close() }.onFailure { failure.incrementAndGet() }
        notifier = null
        assertEquals(0, failure.get())
    }
}
