// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import java.io.File

/**
 * The one fact Grom has to remember to be able to measure an unexpected stop
 * (D83).
 *
 * `onCreate` writes the session start and `cleanStop = false`; `onDestroy` writes
 * `cleanStop = true`. If the next start reads `cleanStop = false`, the previous
 * process ended without `onDestroy` — that is a fact, not an inference. Nothing
 * else in Grom polls a file, and this store adds no timer: it is two writes per
 * process lifetime, both from calls the service already makes.
 *
 * What it deliberately does **not** claim is *when* the process died. The marker
 * only knows when the next start noticed, so [RunMarker.lastUnexpectedStopMs] is
 * the detection time. Reporting the previous start time as a death time would be
 * a guess dressed up as a measurement, and this whole screen exists to avoid that.
 *
 * Storage follows D40: one small JSON file, owner-only, temp-then-rename, and a
 * hard cap checked before the write.
 */
class RunMarkerStore(
    private val file: File,
    private val maxFileBytes: Int = MAX_FILE_BYTES
) {

    @Volatile
    var lastError: String? = null
        private set

    /** The marker as last read; never null, so a corrupt file is not a crash. */
    @Volatile
    private var cached: RunMarker? = null

    @Synchronized
    fun current(): RunMarker = cached ?: load().also { cached = it }

    /**
     * Called from `GromService.onCreate`, before the runtime starts.
     *
     * Returns the marker **as observed before this session**, so the caller can
     * ask [RunMarker.diedUncleanly] about the previous process. If the file was
     * unreadable, the session starts fresh and the counter resets — an unknown
     * past is not turned into an invented failure.
     */
    @Synchronized
    fun beginSession(nowMs: Long): RunMarker {
        val previous = current()
        val unclean = previous.cleanStop == false
        val fresh = RunMarker(
            sessionStartedAtMs = nowMs,
            cleanStop = false,
            unexpectedStops = if (unclean) previous.unexpectedStops + 1 else previous.unexpectedStops,
            lastUnexpectedStopMs = if (unclean) nowMs else previous.lastUnexpectedStopMs,
            previousSessionStartedAtMs = if (unclean) {
                previous.sessionStartedAtMs
            } else {
                previous.previousSessionStartedAtMs
            },
            // Carried, not reset: the last clean stop is what lets the screen say
            // "it has stopped cleanly before", and a new start must not erase it.
            lastCleanStopMs = previous.lastCleanStopMs
        )
        write(fresh)
        return previous
    }

    /** Called from `GromService.onDestroy`, after the runtime has stopped. */
    @Synchronized
    fun endSessionCleanly(nowMs: Long) {
        val marker = current()
        if (marker.sessionStartedAtMs <= 0L) {
            // No session was ever begun; writing a clean stop would erase the
            // only evidence that something ended without a begin.
            return
        }
        write(
            marker.copy(
                cleanStop = true,
                lastCleanStopMs = nowMs
            )
        )
    }

    /** Used by the reset action and by tests; leaves a clean, empty marker. */
    @Synchronized
    fun clear() {
        cached = RunMarker()
        write(RunMarker())
    }

    // ------------------------------------------------------------------ storage

    private fun load(): RunMarker {
        lastError = null
        if (!file.isFile) return RunMarker()
        return try {
            val text = file.readText()
            if (text.length > maxFileBytes) {
                lastError = "doctor marker is larger than $maxFileBytes bytes"
                return RunMarker()
            }
            val root = JsonParser.parseObjectOrNull(text)
            if (root == null) {
                lastError = "doctor marker is not a JSON object"
                return RunMarker()
            }
            RunMarker(
                sessionStartedAtMs = (root["sessionStartedAtMs"] as? Number)?.toLong() ?: 0L,
                cleanStop = root["cleanStop"] as? Boolean,
                unexpectedStops = ((root["unexpectedStops"] as? Number)?.toInt() ?: 0)
                    .coerceIn(0, MAX_UNEXPECTED_STOPS),
                lastUnexpectedStopMs = (root["lastUnexpectedStopMs"] as? Number)?.toLong()
                    ?.takeIf { it > 0L },
                previousSessionStartedAtMs = (root["previousSessionStartedAtMs"] as? Number)
                    ?.toLong()?.takeIf { it > 0L },
                lastCleanStopMs = (root["lastCleanStopMs"] as? Number)?.toLong()
                    ?.takeIf { it > 0L }
            )
        } catch (failure: Exception) {
            lastError = "doctor marker unreadable: ${failure.javaClass.simpleName}"
            RunMarker()
        }
    }

    private fun write(marker: RunMarker) {
        file.parentFile?.mkdirs()
        val text = Json.encode(
            linkedMapOf(
                "version" to 1,
                "sessionStartedAtMs" to marker.sessionStartedAtMs,
                "cleanStop" to marker.cleanStop,
                "unexpectedStops" to marker.unexpectedStops,
                "lastUnexpectedStopMs" to marker.lastUnexpectedStopMs,
                "previousSessionStartedAtMs" to marker.previousSessionStartedAtMs,
                "lastCleanStopMs" to marker.lastCleanStopMs
            )
        )
        if (text.length > maxFileBytes) {
            lastError = "doctor marker would exceed $maxFileBytes bytes"
            return
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        harden(temp)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        harden(file)
        cached = marker
        lastError = null
    }

    private fun harden(target: File) {
        runCatching { target.setReadable(false, false) }
        runCatching { target.setReadable(true, true) }
        runCatching { target.setWritable(false, false) }
        runCatching { target.setWritable(true, true) }
    }

    companion object {
        const val FILE_NAME: String = "doctor-run.json"

        /**
         * Seven small fields. A larger file means something else wrote it, and
         * the answer to that is to ignore it, not to read it.
         */
        const val MAX_FILE_BYTES: Int = 4 * 1024

        /** A runaway counter helps nobody; the screen only needs "it happens". */
        const val MAX_UNEXPECTED_STOPS: Int = 1000
    }
}

/**
 * The persisted marker. `cleanStop = null` means the file was absent or
 * unreadable, which is different from `false` (a process that really did die).
 */
data class RunMarker(
    val sessionStartedAtMs: Long = 0L,
    val cleanStop: Boolean? = null,
    val unexpectedStops: Int = 0,
    val lastUnexpectedStopMs: Long? = null,
    val previousSessionStartedAtMs: Long? = null,
    val lastCleanStopMs: Long? = null
) {
    /** True only for an observed `false`: absent or corrupt is not "died". */
    fun diedUncleanly(): Boolean = cleanStop == false
}
