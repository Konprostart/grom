// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap

/**
 * In-memory session tokens for password logins (D42, S5-R014).
 *
 * A token is 32 random bytes rendered as base64url without padding (43
 * characters). Only SHA-256 of the token is stored, so a memory dump does not
 * reveal a usable credential; the token itself is never logged. The map is
 * access-ordered and holds at most [maxSessions] entries: the least recently
 * used session is evicted first, which bounds memory without a background
 * sweeper. All access is synchronized.
 */
class SessionStore(
    private val maxSessions: Int = 16,
    private val ttlMs: Long = 12L * 60 * 60 * 1000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class Issued(val token: String, val expiresAtMs: Long)

    /** token hash -> expiry on the wall clock. */
    private val sessions = LinkedHashMap<String, Long>(16, 0.75f, true)

    @Synchronized
    fun issue(): Issued {
        val now = clock()
        prune(now)
        val token = generateToken()
        val expiresAt = now + ttlMs
        sessions[hash(token)] = expiresAt
        evict()
        return Issued(token, expiresAt)
    }

    @Synchronized
    fun validate(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val now = clock()
        prune(now)
        val expiresAt = sessions[hash(token)] ?: return false
        return expiresAt > now
    }

    @Synchronized
    fun revoke(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        prune(clock())
        return sessions.remove(hash(token)) != null
    }

    @Synchronized
    fun revokeAll() {
        sessions.clear()
    }

    @Synchronized
    fun activeCount(): Int {
        prune(clock())
        return sessions.size
    }

    private fun prune(now: Long) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= now) iterator.remove()
        }
    }

    private fun evict() {
        while (sessions.size > maxSessions) {
            val iterator = sessions.keys.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(BYTE_LENGTH)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** SHA-256 as lowercase hex; only this form is kept in memory. */
    private fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    companion object {
        const val TOKEN_LENGTH: Int = 43

        private const val BYTE_LENGTH = 32
        private val HEX = "0123456789abcdef".toCharArray()
        private val random = SecureRandom()
    }
}
