// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import java.security.MessageDigest
import java.util.Base64

/**
 * Replay protection for signed requests (D42, S5-R018).
 *
 * A nonce is remembered for [windowMs]; a second request with the same nonce
 * inside that window is refused. Entries are pruned on every call and the map
 * is bounded by [maxEntries], so the cache cannot be used to exhaust memory.
 * All access is synchronized.
 */
class NonceCache(
    private val maxEntries: Int = 4096,
    private val windowMs: Long = 600_000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** insertion order: oldest nonce is evicted first. */
    private val seen = LinkedHashMap<String, Long>()

    /**
     * Returns `true` when [nonce] was absent and is now stored, `false` when it
     * was already seen inside the window.
     */
    @Synchronized
    fun remember(nonce: String): Boolean {
        val now = clock()
        prune(now)
        if (seen.containsKey(nonce)) return false
        seen[nonce] = now
        while (seen.size > maxEntries) {
            val iterator = seen.keys.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        return true
    }

    @Synchronized
    fun size(): Int {
        prune(clock())
        return seen.size
    }

    private fun prune(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value >= windowMs) iterator.remove()
        }
    }
}

/**
 * Ed25519 request-signature verification for the public data plane
 * (D42, S5-R017/S5-R018).
 *
 * The signed string covers the method, the path, a timestamp, a nonce and the
 * SHA-256 of the body:
 *
 * ```
 * grom-ed25519-v1\n
 * <METHOD>\n
 * <path>\n
 * <timestamp>\n
 * <nonce>\n
 * <sha256hex(body)>
 * ```
 *
 * Query strings are deliberately not signed: any state-changing parameter MUST
 * be carried in the signed JSON body, never in the URL. The nonce header is
 * only recorded after the signature verifies, so an unauthenticated caller
 * cannot fill [NonceCache] with garbage.
 */
class RequestSigner(
    private val keyLookup: (String) -> ByteArray?,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val maxSkewSeconds: Long = 300,
    private val nonceCache: NonceCache = NonceCache()
) {

    sealed class Result {
        data object Ok : Result()

        data class Rejected(val code: String, val message: String) : Result()
    }

    /**
     * The exact string a client must sign. [method] is upper-cased, the path is
     * used verbatim, and the body contributes only its lowercase SHA-256 hex.
     */
    fun canonical(
        method: String,
        path: String,
        body: String,
        timestamp: String,
        nonce: String
    ): String = buildString {
        append(SCHEME).append('\n')
        append(method.uppercase()).append('\n')
        append(path).append('\n')
        append(timestamp).append('\n')
        append(nonce).append('\n')
        append(sha256Hex(body.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Verifies the four `x-grom-*` headers. Header names are looked up through
     * [header], which the caller has already normalized to lower case. Every
     * failure maps to a stable code; none of them discloses whether a key id
     * exists beyond the documented `SIGNATURE_UNKNOWN_KEY` case.
     */
    fun verify(
        method: String,
        path: String,
        body: String,
        header: (String) -> String?
    ): Result {
        val keyId = header(HEADER_KEY)?.trim()
        val timestamp = header(HEADER_TIMESTAMP)?.trim()
        val nonce = header(HEADER_NONCE)?.trim()
        val signatureText = header(HEADER_SIGNATURE)?.trim()

        if (keyId.isNullOrEmpty() ||
            timestamp.isNullOrEmpty() ||
            nonce.isNullOrEmpty() ||
            signatureText.isNullOrEmpty()
        ) {
            return Result.Rejected("SIGNATURE_MISSING", "signed request headers are incomplete")
        }

        val now = clock()
        val seconds = timestamp.toLongOrNull()
        if (seconds == null ||
            seconds < now - maxSkewSeconds ||
            seconds > now + maxSkewSeconds
        ) {
            return Result.Rejected(
                "SIGNATURE_STALE",
                "request timestamp is outside the accepted window"
            )
        }

        if (!NONCE_PATTERN.matches(nonce)) {
            return Result.Rejected("SIGNATURE_BAD_NONCE", "nonce is missing or malformed")
        }

        // The lookup returns public material only; an unknown key id tells the
        // caller nothing about any registered key.
        val publicKey = keyLookup(keyId)
        if (publicKey == null || publicKey.size != PUBLIC_KEY_BYTES) {
            return Result.Rejected("SIGNATURE_UNKNOWN_KEY", "unknown key id")
        }

        val signature = decodeBase64(signatureText)
        if (signature == null || signature.size != SIGNATURE_BYTES) {
            return Result.Rejected("SIGNATURE_INVALID", "signature is malformed")
        }

        val message = canonical(method, path, body, timestamp, nonce).toByteArray(Charsets.UTF_8)
        if (!Ed25519.verify(publicKey, message, signature)) {
            return Result.Rejected("SIGNATURE_INVALID", "signature does not verify")
        }

        // Remembered only after authentication, so a caller that cannot sign
        // cannot grow the cache.
        if (!nonceCache.remember(nonce)) {
            return Result.Rejected("SIGNATURE_REPLAYED", "nonce was already used")
        }

        return Result.Ok
    }

    private fun decodeBase64(value: String): ByteArray? {
        if (value.isEmpty() || value.length > MAX_SIGNATURE_TEXT_LENGTH) return null
        for (decoder in DECODERS) {
            val bytes = runCatching { decoder.decode(value) }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    companion object {
        const val HEADER_KEY: String = "x-grom-key"
        const val HEADER_TIMESTAMP: String = "x-grom-timestamp"
        const val HEADER_NONCE: String = "x-grom-nonce"
        const val HEADER_SIGNATURE: String = "x-grom-signature"

        const val PUBLIC_KEY_BYTES: Int = 32
        const val SIGNATURE_BYTES: Int = 64

        private const val SCHEME = "grom-ed25519-v1"
        private const val MAX_SIGNATURE_TEXT_LENGTH = 128
        private val NONCE_PATTERN = Regex("[A-Za-z0-9_-]{8,128}")
        private val HEX = "0123456789abcdef".toCharArray()
        private val DECODERS = listOf(Base64.getDecoder(), Base64.getUrlDecoder())
    }
}
