// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

import app.grom.server.projects.ProjectRecord

/**
 * Maps an audit action to a notification (D65).
 *
 * This is the security-critical part of the feature, so it is a pure function
 * with no state, no clock and no transport: [classify] either returns one of the
 * fixed [NotifyEvent] messages or `null`. An action that is not in the table is
 * ignored — there is no fallback that forwards an unknown string.
 *
 * The audit `detail` is *parsed*, never copied:
 *   - `ssh.failed` carries `code=SSH_UNAVAILABLE`; only a value matching
 *     [CODE_PATTERN] is accepted, and `ssh.connect`/`ssh.closed` carry a host
 *     that is dropped entirely;
 *   - `project.announce`/`project.remove` carry `id=…`; the id must match the
 *     registry's own pattern before it may appear in a message.
 */
object NotifyRules {

    /** Stable failure codes: `SSH_TIMEOUT`, `HOST_KEY_MISMATCH`, … */
    val CODE_PATTERN: Regex = Regex("^[A-Z][A-Z0-9_]{2,39}$")

    private val CODE_IN_DETAIL = Regex("(?:^|\\s)code=([A-Za-z0-9_]{1,40})(?:\\s|$)")

    private val ID_IN_DETAIL = Regex("(?:^|\\s)id=([A-Za-z0-9-]{1,40})(?:\\s|$)")

    private val QUARANTINED_IN_DETAIL = Regex("(?:^|\\s)quarantined=(true|false)(?:\\s|$)")

    fun classify(action: String, detail: String): NotifyMessage? = when (action) {
        "server.start" -> NotifyMessage(NotifyEvent.SERVER_STARTED)
        "server.stop" -> NotifyMessage(NotifyEvent.SERVER_STOPPED)

        "agent.ready" -> NotifyMessage(NotifyEvent.AGENT_READY)
        // A crash that quarantines the agent is one event with one message:
        // announcing "restarting" next to "quarantined" would be a lie.
        "agent.crash" -> if (isQuarantined(detail)) {
            NotifyMessage(NotifyEvent.AGENT_QUARANTINED)
        } else {
            NotifyMessage(NotifyEvent.AGENT_CRASHED)
        }
        "agent.fatal" -> NotifyMessage(NotifyEvent.AGENT_QUARANTINED)

        "super.start" -> NotifyMessage(NotifyEvent.DATA_PLANE_STARTED)
        "super.stop" -> NotifyMessage(NotifyEvent.DATA_PLANE_STOPPED)
        "super.refused" -> NotifyMessage(NotifyEvent.DATA_PLANE_REFUSED, codeOf(detail))
        "super.bind.failed" -> NotifyMessage(NotifyEvent.DATA_PLANE_BIND_FAILED)

        "ssh.connect" -> NotifyMessage(NotifyEvent.BRIDGE_CONNECTED)
        "ssh.closed" -> NotifyMessage(NotifyEvent.BRIDGE_DISCONNECTED)
        "ssh.failed" -> NotifyMessage(NotifyEvent.BRIDGE_FAILED, codeOf(detail))
        "ssh.hostkey.mismatch" -> NotifyMessage(NotifyEvent.BRIDGE_HOSTKEY_MISMATCH)

        "project.announce" -> NotifyMessage(NotifyEvent.PROJECT_REGISTERED, projectIdOf(detail))
        "project.remove" -> NotifyMessage(NotifyEvent.PROJECT_REMOVED, projectIdOf(detail))

        "lightning.probe" -> if (detail.contains("reachable=false")) {
            NotifyMessage(NotifyEvent.LIGHTNING_UNREACHABLE)
        } else {
            null
        }

        // Deliberate operator actions and first-use handshakes: no message.
        "ssh.disconnect", "ssh.hostkey.learned", "ssh.hostkey.unverified" -> null

        else -> null
    }

    private fun isQuarantined(detail: String): Boolean =
        QUARANTINED_IN_DETAIL.find(detail)?.groupValues?.get(1) == "true"

    /** The code, or null when the detail does not carry a well-formed one. */
    private fun codeOf(detail: String): String? {
        val raw = CODE_IN_DETAIL.find(detail)?.groupValues?.get(1) ?: return null
        return raw.takeIf { CODE_PATTERN.matches(it) }
    }

    /** The project id, or null when it is missing or not a valid id. */
    private fun projectIdOf(detail: String): String? {
        val raw = ID_IN_DETAIL.find(detail)?.groupValues?.get(1) ?: return null
        return raw.takeIf { ProjectRecord.ID_PATTERN.matches(it) }
    }
}
