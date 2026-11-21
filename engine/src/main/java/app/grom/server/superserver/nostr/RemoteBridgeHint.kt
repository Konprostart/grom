// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.nostr

/**
 * A free-text label for the external bridge an operator runs elsewhere (D52).
 *
 * Grom does **not** start, supervise or connect a tunnel. This value exists so
 * the operator can recognise the VPS behind the tunnel in the UI and in
 * "Copy config" without keeping the fact in their head.
 *
 * Because it is free text written on a phone, it is treated as hostile to
 * secrets: a bridge hint that looks like a private key, a password or an API key
 * is refused outright. A hint is a signpost, not a place to paste credentials.
 */
data class RemoteBridgeHint(
    val label: String = "",
    val description: String = ""
) {

    fun isEmpty(): Boolean = label.isBlank() && description.isBlank()

    fun normalized(): RemoteBridgeHint = RemoteBridgeHint(label.trim(), description.trim())

    /** Returns a human problem when the hint is unacceptable, or null when it is fine. */
    fun validate(): String? {
        if (label.length > MAX_LABEL) {
            return "the bridge label must be at most $MAX_LABEL characters"
        }
        if (description.length > MAX_DESCRIPTION) {
            return "the bridge description must be at most $MAX_DESCRIPTION characters"
        }
        if ((label + description).any { it.isISOControl() }) {
            return "control characters are not allowed in a bridge hint"
        }
        val text = "$label\n$description"
        for (pattern in SECRET_PATTERNS) {
            if (pattern.containsMatchIn(text)) {
                return "a secret must never be stored in a bridge hint"
            }
        }
        return null
    }

    companion object {

        const val MAX_LABEL: Int = 80
        const val MAX_DESCRIPTION: Int = 240

        /** Shapes that must never be persisted as a "hint". */
        private val SECRET_PATTERNS: List<Regex> = listOf(
            Regex("(?i)nsec1[a-z0-9]{20,}"),
            Regex("(?i)-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Regex("(?i)\\bpassword\\s*[=:]"),
            Regex("(?i)\\bapi[_-]?key\\s*[=:]"),
            Regex("(?i)\\btoken\\s*[=:]"),
            Regex("(?i)\\bsecret\\s*[=:]"),
            Regex("(?i)\\bbearer\\s+[a-z0-9._-]{12,}")
        )

        /** Parses the persisted object; null when the object is absent or empty. */
        fun fromJson(raw: Any?): RemoteBridgeHint? {
            val map = raw as? Map<*, *> ?: return null
            val label = (map["label"] as? String)?.take(MAX_DESCRIPTION) ?: ""
            val description = (map["description"] as? String)?.take(MAX_DESCRIPTION) ?: ""
            val hint = RemoteBridgeHint(label.trim(), description.trim())
            return hint.takeUnless { it.isEmpty() }
        }
    }
}
