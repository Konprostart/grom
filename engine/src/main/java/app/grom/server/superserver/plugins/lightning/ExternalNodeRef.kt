// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.lightning

/**
 * A reference to a Lightning node that lives somewhere else (D63).
 *
 * Grom is an adapter, never a wallet, and that does not change here: LND,
 * Core Lightning and NWC need a long-running daemon and a permanent connection,
 * which belongs on the operator's own server — not on a phone. This record lets
 * the operator *describe* that node so the phone can hand the description to an
 * external bridge, exactly like [app.grom.server.superserver.nostr.RemoteBridgeHint].
 *
 * Nothing in this file opens a connection, resolves a name or stores a
 * credential. [endpointHint] is a host or `host:port`, never a URL with a
 * scheme, and it deliberately never leaves the device: `/v1/status` and the
 * "Copy config" document carry the kind and the label only.
 */
enum class ExternalNodeKind(val wireName: String) {
    NONE("none"),
    LND("lnd"),
    CLN("cln"),
    NWC("nwc");

    companion object {
        fun fromWire(value: String?): ExternalNodeKind =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: NONE
    }
}

data class ExternalNodeRef(
    val kind: ExternalNodeKind = ExternalNodeKind.NONE,
    val label: String = "",
    val endpointHint: String = ""
) {

    /** A record with nothing in it is the same as no record at all. */
    fun isEmpty(): Boolean =
        kind == ExternalNodeKind.NONE && label.isBlank() && endpointHint.isBlank()

    /** Null when valid, otherwise a message safe to show the operator. */
    fun validate(): String? {
        if (isEmpty()) return null
        if (kind == ExternalNodeKind.NONE) {
            return "choose a node kind (lnd, cln or nwc) or clear the reference"
        }
        if (label.length > MAX_LABEL_CHARS || label.any { it.isISOControl() }) {
            return "label must be at most $MAX_LABEL_CHARS characters without control characters"
        }
        if (looksLikeSecret(label)) {
            return "label must not contain a secret"
        }
        if (endpointHint.isNotEmpty()) {
            if (endpointHint.length > MAX_ENDPOINT_CHARS) {
                return "endpoint must be at most $MAX_ENDPOINT_CHARS characters"
            }
            if (!ENDPOINT_PATTERN.matches(endpointHint)) {
                return "endpoint must be a host or host:port (no scheme, path or user@)"
            }
            if (looksLikeSecret(endpointHint)) {
                return "endpoint must not contain a secret"
            }
        }
        return null
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "kind" to kind.wireName,
        "label" to label.trim(),
        "endpointHint" to endpointHint.trim()
    )

    /** The part that may leave the device: no endpoint, no credential. */
    fun toPublicJson(): Map<String, Any?> = linkedMapOf(
        "kind" to kind.wireName,
        "label" to label.trim(),
        "configured" to (kind != ExternalNodeKind.NONE)
    )

    companion object {

        const val MAX_LABEL_CHARS: Int = 64
        const val MAX_ENDPOINT_CHARS: Int = 253

        /** Hostname, IPv4 or IPv6 literal, with an optional numeric port. */
        val ENDPOINT_PATTERN: Regex =
            Regex("^[A-Za-z0-9](?:[A-Za-z0-9._:\\-]{0,251}[A-Za-z0-9])?$")

        /**
         * The same anti-secret rule as the bridge hint: an operator who pastes a
         * wallet key into a text box must be told, not silently stored.
         */
        private val SECRET_MARKERS: List<String> = listOf(
            "nsec1",
            "PRIVATE KEY",
            "password=",
            "passwd=",
            "token=",
            "api_key=",
            "apikey=",
            "secret="
        )

        fun looksLikeSecret(value: String): Boolean {
            val lowered = value.lowercase()
            return SECRET_MARKERS.any { marker ->
                lowered.contains(marker.lowercase())
            }
        }

        fun fromJson(raw: Any?): ExternalNodeRef? {
            val map = raw as? Map<*, *> ?: return null
            val kind = ExternalNodeKind.fromWire(map["kind"] as? String)
            val label = (map["label"] as? String)?.trim().orEmpty()
            val endpoint = (map["endpointHint"] as? String)?.trim().orEmpty()
            val ref = ExternalNodeRef(kind, label, endpoint)
            return ref.takeUnless { it.isEmpty() }
        }
    }
}
