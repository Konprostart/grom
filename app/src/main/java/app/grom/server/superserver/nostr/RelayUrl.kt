// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.nostr

/**
 * Reference relay URLs (D52).
 *
 * Grom stores a short list of relay URLs so an external bridge can read them
 * from `/v1/status` or "Copy config". **No relay connection is ever opened from
 * Grom** — this is a validator and canonicaliser, nothing more. That is why a
 * plaintext `ws://` URL is allowed (a local relay), while the UI labels it as
 * plaintext.
 *
 * Accepted: `wss://` or `ws://`, a host (name, IPv4 or bracketed IPv6), an
 * optional port in 1..65535, and an optional path.
 * Rejected: any other scheme, userinfo (`user:pass@`), query, fragment, control
 * characters, whitespace and anything longer than [MAX_LENGTH].
 */
object RelayUrl {

    const val MAX_RELAYS: Int = 8
    const val MAX_LENGTH: Int = 512

    private val SCHEMES = listOf("wss://", "ws://")

    /**
     * Returns the canonical URL (lowercase scheme and host, original port and
     * path), or null when the input is not a usable relay URL.
     */
    fun normalizeRelay(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_LENGTH) return null
        if (raw.any { it.isWhitespace() || it.isISOControl() }) return null

        val lower = raw.lowercase()
        val scheme = SCHEMES.firstOrNull { lower.startsWith(it) } ?: return null
        val rest = raw.substring(scheme.length)
        if (rest.isEmpty()) return null
        // A relay URL never carries credentials, a query or a fragment.
        if (rest.contains('@') || rest.contains('?') || rest.contains('#')) return null

        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else ""
        if (authority.isEmpty()) return null

        val host: String
        val port: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1)
            val tail = authority.substring(close + 1)
            port = when {
                tail.isEmpty() -> null
                tail.startsWith(":") -> tail.substring(1)
                else -> return null
            }
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1)
            } else {
                host = authority
                port = null
            }
        }

        if (!isValidHost(host)) return null
        if (port != null) {
            val value = port.toIntOrNull() ?: return null
            if (value !in 1..65535) return null
        }
        if (path.any { it.isISOControl() }) return null

        val canonicalHost = host.lowercase()
        val canonicalAuthority = if (port != null) "$canonicalHost:$port" else canonicalHost
        return scheme + canonicalAuthority + path
    }

    /**
     * Normalises and de-duplicates while preserving order. Returns null when any
     * entry is invalid or the list exceeds [MAX_RELAYS].
     */
    fun normalizeRelays(input: List<String>): List<String>? {
        val out = LinkedHashSet<String>()
        for (item in input) {
            out.add(normalizeRelay(item) ?: return null)
        }
        if (out.size > MAX_RELAYS) return null
        return out.toList()
    }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 255) return false
        if (host.startsWith("[") || host.endsWith("]")) {
            if (!host.startsWith("[") || !host.endsWith("]")) return false
            val inner = host.substring(1, host.length - 1)
            return inner.isNotEmpty() &&
                inner.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        }
        if (host.startsWith(".") || host.endsWith(".")) return false
        if (host.contains("..")) return false
        return host.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-'
        }
    }
}
