// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

/**
 * The second readiness line the agent prints after `GROM_READY <pid>`
 * (S3-R011 keeps the first line verbatim; this one is additive):
 *
 *     GROM_AGENT_READY <pid> <port>
 *
 * The port is an ephemeral 127.0.0.1 port the agent bound for its private
 * control API. The server needs it to proxy `POST /grom/v1/agent/exec`
 * instead of pretending the operation succeeded.
 */
object AgentChannel {

    const val READY_PREFIX: String = "GROM_AGENT_READY"

    /**
     * The ops both sides accept. Kept here so a JVM test can assert that the
     * server whitelist and the agent cannot drift apart silently.
     */
    val OPS: Set<String> = linkedSetOf("ping", "echo", "status", "kv.get", "kv.put")

    data class Ready(val pid: Long, val port: Int)

    private val READY = Regex("^GROM_AGENT_READY[ \\t]+(\\d+)[ \\t]+(\\d+)[ \\t]*$")

    fun observe(line: String?): Ready? {
        if (line == null) return null
        val match = READY.find(line.trim()) ?: return null
        val pid = match.groupValues[1].toLongOrNull() ?: return null
        val port = match.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Ready(pid, port)
    }

    /** The announced port belongs to the child we spawned, just like S3-R013. */
    fun matches(ready: Ready?, expectedPid: Long): Boolean =
        ready != null && expectedPid > 0 && ready.pid == expectedPid
}
