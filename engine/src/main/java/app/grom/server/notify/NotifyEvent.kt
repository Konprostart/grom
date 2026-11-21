// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

/**
 * The fixed event table (D65, D66).
 *
 * Every message Grom can send is one of these sentences. Nothing is assembled
 * from a log line, an exception or a request, which is what keeps the alert
 * channel from becoming an exfiltration path: the only parts that vary are a
 * stable code and a validated project id.
 *
 * The texts deliberately contain no address, no amount and no identifier.
 */
enum class NotifyEvent(val code: String, private val sentence: String) {

    SERVER_STARTED("SERVER_STARTED", "server started"),
    SERVER_STOPPED("SERVER_STOPPED", "server stopped"),

    AGENT_READY("AGENT_READY", "agent is running"),
    AGENT_CRASHED("AGENT_CRASHED", "agent crashed; restarting"),
    AGENT_QUARANTINED("AGENT_QUARANTINED", "agent quarantined after repeated crashes"),

    DATA_PLANE_STARTED("DATA_PLANE_STARTED", "public API started"),
    DATA_PLANE_STOPPED("DATA_PLANE_STOPPED", "public API stopped"),
    DATA_PLANE_REFUSED("DATA_PLANE_REFUSED", "public API refused"),
    DATA_PLANE_BIND_FAILED("DATA_PLANE_BIND_FAILED", "public API could not bind"),

    BRIDGE_CONNECTED("BRIDGE_CONNECTED", "external server connected"),
    BRIDGE_DISCONNECTED("BRIDGE_DISCONNECTED", "external server disconnected"),
    BRIDGE_FAILED("BRIDGE_FAILED", "external server connection failed"),
    BRIDGE_HOSTKEY_MISMATCH("BRIDGE_HOSTKEY_MISMATCH", "external server key changed; refused"),

    PROJECT_REGISTERED("PROJECT_REGISTERED", "app registered"),
    PROJECT_REMOVED("PROJECT_REMOVED", "app registration removed"),

    LIGHTNING_UNREACHABLE("LIGHTNING_UNREACHABLE", "Lightning backend unreachable"),

    /** The operator's own "Send test message"; never a state change. */
    TEST("TEST", "test message; alerts are working");

    /**
     * The whole message. [subject] is either a stable code that [NotifyRules]
     * matched against a strict pattern, or a project id that passed the same
     * rule the registry applies — never free text.
     */
    fun render(subject: String? = null): String {
        val suffix = subject?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "$PREFIX$sentence$suffix."
    }

    companion object {
        const val PREFIX: String = "Grom: "

        /** Total cap on the rendered message, well under the provider's limit. */
        const val MAX_MESSAGE_CHARS: Int = 200
    }
}

/** One classified audit event, ready to queue. */
data class NotifyMessage(
    val event: NotifyEvent,
    val subject: String? = null
) {
    fun text(): String = event.render(subject).take(NotifyEvent.MAX_MESSAGE_CHARS)
}
