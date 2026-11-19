// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Bearer token handling (D8, S2-R010/S2-R011, S1-R013).
 *
 * A token is 32 random bytes, base64url without padding — 43 characters.
 * Comparisons are constant-time and never log the value.
 */
object Token {

    const val BYTE_LENGTH: Int = 32
    const val LENGTH: Int = 43

    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(BYTE_LENGTH)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hasValidShape(candidate: String?): Boolean {
        if (candidate == null || candidate.length != LENGTH) return false
        return candidate.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /** Constant-time comparison. Never short-circuits on length mismatch content. */
    fun matches(expected: String?, provided: String?): Boolean {
        if (expected == null || provided == null) return false
        if (expected.isEmpty()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * First 8 hex characters of SHA-256. This is the only form of a token that
     * may appear in a log or an audit entry.
     */
    fun fingerprint(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(8)
        for (i in 0 until 4) {
            val v = digest[i].toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** Extracts the credential from `Authorization: Bearer x` or `X-Grom-Token: x`. */
    fun fromHeaders(authorization: String?, xGromToken: String?): String? {
        authorization?.let { raw ->
            val trimmed = raw.trim()
            if (trimmed.length > BEARER_PREFIX.length &&
                trimmed.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)
            ) {
                return trimmed.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
            }
        }
        return xGromToken?.trim()?.ifEmpty { null }
    }

    private const val BEARER_PREFIX = "Bearer "
    private val HEX = "0123456789abcdef".toCharArray()
}
