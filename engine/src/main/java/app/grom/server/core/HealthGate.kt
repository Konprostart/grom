// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

/**
 * Readiness protocol between Grom and the agent (D25, S3-R011…S3-R013).
 *
 * The agent announces readiness on stdout:
 *   GROM_READY <pid>
 * and may abort deliberately with:
 *   GROM_FATAL <message>
 */
object HealthGate {

    sealed class Observation {
        data class Ready(val pid: Long) : Observation()
        data class Fatal(val message: String) : Observation()
        object Unknown : Observation()
    }

    private val READY = Regex("^GROM_READY[ \t]+(\\d+)[ \t]*$")
    private val FATAL = Regex("^GROM_FATAL[ \t]*(.*)$")

    fun observe(line: String?): Observation {
        if (line == null) return Observation.Unknown
        val trimmed = line.trim()
        READY.find(trimmed)?.let { return Observation.Ready(it.groupValues[1].toLong()) }
        FATAL.find(trimmed)?.let { return Observation.Fatal(it.groupValues[1]) }
        return Observation.Unknown
    }

    /**
     * The announced pid must be the pid we actually spawned (S3-R013), so a
     * wrapper that daemonises cannot claim readiness on behalf of a dead child.
     */
    fun pidMatches(observation: Observation, expectedPid: Long): Boolean =
        observation is Observation.Ready && observation.pid == expectedPid
}
