// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.sdk.GromClient
import app.grom.sdk.ipc.IGromServer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The typed project surface for a host application that embeds the engine (D61).
 *
 * [GromClient] is deliberately thin: it binds, it unbinds and it runs a call.
 * Without this class every host would hand-build JSON for project registration
 * and key/value access, and would have to re-implement the heartbeat timer —
 * which is exactly how the documented 30-second heartbeat ended up being
 * promised but not implemented.
 *
 * Honesty rules, unchanged from the rest of the engine:
 *   - a write is [LocalWrite.Ok] only when the agent really answered `200`;
 *   - a missing `node:sqlite` is [LocalWrite.StorageUnavailable], never `Ok`;
 *   - [startHeartbeat] repeats the announcement, it does not invent liveness —
 *     the dashboard derives `alive` from the announcements themselves.
 *
 * Every call needs a bound [GromClient]. When the binder is gone the methods
 * return `null` / [LocalWrite.Rejected] rather than a fabricated success.
 */
class GromProject(
    private val client: GromClient,
    val projectId: String,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** What a host registers. Mirrors [GromEmbed.Options]. */
    data class Registration(
        val title: String,
        val version: String = "",
        val packageName: String = "",
        val capabilities: Set<String> = emptySet()
    )

    sealed class LocalWrite {
        /** The agent answered 200. [value] is the stored value for `get`. */
        data class Ok(val found: Boolean, val value: String?) : LocalWrite()

        /** The runtime has no `node:sqlite`; nothing was read or written. */
        data object StorageUnavailable : LocalWrite()

        /** Anything else: the engine refused, or the agent answered another error. */
        data class Rejected(val code: String, val message: String) : LocalWrite()
    }

    /** The last time an announcement actually reached the engine. */
    @Volatile
    var lastAnnouncedAtMs: Long = 0L
        private set

    @Volatile
    private var heartbeat: ScheduledExecutorService? = null

    @Volatile
    private var heartbeatTask: ScheduledFuture<*>? = null

    /** Registers or refreshes this project. False when the call did not land. */
    fun announce(
        registration: Registration,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        val body = Json.encode(
            mapOf(
                "id" to projectId,
                "title" to registration.title,
                "version" to registration.version,
                "package" to registration.packageName,
                "capabilities" to registration.capabilities.sorted()
            )
        )
        val stored = client.call(false) { api ->
            runCatching { api.announceProject(body) }.isSuccess
        }
        if (stored) lastAnnouncedAtMs = clock()
        onResult?.invoke(stored)
        return stored
    }

    /** Starts a repeating announcement; the returned function stops it. */
    fun startHeartbeat(
        registration: Registration,
        intervalMs: Long = DEFAULT_HEARTBEAT_MS
    ): () -> Unit {
        stopHeartbeat()
        val period = clampInterval(intervalMs)
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "grom-project-heartbeat").apply { isDaemon = true }
        }
        heartbeat = executor
        heartbeatTask = executor.scheduleWithFixedDelay(
            {
                if (client.server == null) {
                    // A dead binder would make every later announcement fail;
                    // stopping is honest, silently retrying is not.
                    stopHeartbeat()
                } else {
                    runCatching { announce(registration) }
                }
            },
            period,
            period,
            TimeUnit.MILLISECONDS
        )
        return { stopHeartbeat() }
    }

    /** Stops the heartbeat thread; safe to call more than once. */
    fun stopHeartbeat() {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
        heartbeat?.shutdownNow()
        heartbeat = null
    }

    /** Every registered project and its derived state. Empty when unbound. */
    fun projects(): List<Map<String, Any?>> {
        val raw = client.call(null) { api ->
            runCatching { api.projectsJson() }.getOrNull()
        } ?: return emptyList()
        val root = JsonParser.parseObjectOrNull(raw) ?: return emptyList()
        val list = root["projects"] as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }
    }

    /** Removes this project's registration. False when the engine refused. */
    fun remove(): Boolean = client.call(false) { api ->
        runCatching { api.removeProject(projectId) }.isSuccess
    }

    /** The sanitised hand-off document (D53); contains no credential. */
    fun configExport(): String? = client.call(null) { api ->
        runCatching { api.configExportJson() }.getOrNull()
    }

    fun put(key: String, value: String): LocalWrite =
        exec("kv.put", """{"key":${quote(key)},"value":${quote(value)}}""")

    fun get(key: String): LocalWrite =
        exec("kv.get", """{"key":${quote(key)}}""")

    /** Runs the Lightning health op now; the only path to a green lamp (D52). */
    fun probeLightning(): Map<String, Any?>? {
        val raw = client.call(null) { api ->
            runCatching { api.lightningProbeJson() }.getOrNull()
        } ?: return null
        return JsonParser.parseObjectOrNull(raw)
    }

    fun apiVersion(): Int? = client.call(null) { api -> api.apiVersion() }

    // ------------------------------------------------------------------ helpers

    private fun exec(op: String, argsJson: String): LocalWrite {
        val response = try {
            client.call<String?>(null) { api: IGromServer ->
                api.projectExec(projectId, op, argsJson)
            }
        } catch (refused: IllegalStateException) {
            // The engine maps its own refusals onto GROM_E_<CODE>: message.
            return parseRefusal(refused.message)
        }
        return parseEnvelope(op, response)
    }

    /** Minimal JSON string quoting; the engine's encoder handles the rest. */
    private fun quote(value: String): String =
        Json.encode(listOf(value)).removeSurrounding("[", "]")

    companion object {
        const val DEFAULT_HEARTBEAT_MS: Long = 30_000
        const val MIN_HEARTBEAT_MS: Long = 5_000
        const val MAX_HEARTBEAT_MS: Long = 10 * 60_000

        /**
         * Maps `{"status":…,"body":…}` onto the typed result.
         *
         * Kept pure and public so the mapping is unit-tested without a binder:
         * this is exactly where a "missing sqlite" answer could be mistaken for
         * success, which the engine must never do.
         */
        fun parseEnvelope(op: String, response: String?): LocalWrite {
            val envelope = response?.let { JsonParser.parseObjectOrNull(it) }
                ?: return LocalWrite.Rejected("NOT_BOUND", "the engine did not answer")
            val status = (envelope["status"] as? Number)?.toInt() ?: 0
            val body = envelope["body"] as? String ?: ""
            if (status == 200) {
                val parsed = JsonParser.parseObjectOrNull(body)
                val result = parsed?.get("result") as? Map<*, *>
                val found = result?.get("found") as? Boolean ?: (op == "kv.put")
                val value = result?.get("value") as? String
                return LocalWrite.Ok(found = found, value = value)
            }
            val parsed = JsonParser.parseObjectOrNull(body)
            val code = (parsed?.get("code") as? String) ?: "HTTP_$status"
            val message = (parsed?.get("message") as? String) ?: ""
            return if (status == 503 && code == "STORAGE_UNAVAILABLE") {
                LocalWrite.StorageUnavailable
            } else {
                LocalWrite.Rejected(code, message)
            }
        }

        /** `GROM_E_<CODE>: detail` as thrown by the service becomes a result. */
        fun parseRefusal(message: String?): LocalWrite.Rejected {
            val text = message.orEmpty()
            val marker = "GROM_E_"
            if (!text.startsWith(marker)) {
                return LocalWrite.Rejected("REFUSED", text)
            }
            val rest = text.removePrefix(marker)
            val code = rest.substringBefore(':', rest).trim()
            val detail = rest.substringAfter(':', "").trim()
            return LocalWrite.Rejected(code.ifEmpty { "REFUSED" }, detail)
        }

        /** The heartbeat period is clamped, so a host cannot set a 0 ms timer. */
        fun clampInterval(intervalMs: Long): Long =
            intervalMs.coerceIn(MIN_HEARTBEAT_MS, MAX_HEARTBEAT_MS)
    }
}
