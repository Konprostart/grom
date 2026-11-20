// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2 password hashing for the super server (D42, S5-R013).
 *
 * The wire form is `pbkdf2-sha256$<iterations>$<base64salt>$<base64hash>`.
 * The plaintext password is never stored and never logged; [matches] compares
 * the derived key with [MessageDigest.isEqual] so the comparison is constant
 * time. A malformed encoded value or a wrong password returns `false` and never
 * throws.
 */
class PasswordHash private constructor(private val encoded: String) {

    /** The encoded hash, exactly as it is persisted in the configuration. */
    val value: String
        get() = encoded

    /**
     * Derives a key with the salt and iteration count stored in this hash and
     * compares it with the stored key. Returns `false` for any malformed value
     * or for a password that does not match.
     */
    fun matches(password: String): Boolean {
        val parsed = parse(encoded) ?: return false
        return runCatching {
            val derived = derive(password, parsed.salt, parsed.iterations)
            MessageDigest.isEqual(derived, parsed.hash)
        }.getOrDefault(false)
    }

    internal class Parsed(
        val iterations: Int,
        val salt: ByteArray,
        val hash: ByteArray
    )

    companion object {

        /** Default work factor. Raising it invalidates nothing: it is in the encoding. */
        const val ITERATIONS: Int = 150_000

        /** Minimum accepted password length. */
        const val MIN_LENGTH: Int = 8

        const val MIN_ITERATIONS: Int = 10_000
        const val MAX_ITERATIONS: Int = 1_000_000

        private const val SCHEME = "pbkdf2-sha256"
        private const val SALT_BYTES = 16
        private const val KEY_BYTES = 32
        private const val MIN_SALT_BYTES = 16
        private const val MAX_SALT_BYTES = 64
        private const val MIN_HASH_BYTES = 32
        private const val MAX_HASH_BYTES = 64

        /**
         * Upper bound on the encoded string. The legitimate maximum is well
         * below this; the bound keeps [decode] cheap on hostile input.
         */
        private const val MAX_ENCODED_LENGTH = 512

        private val random = SecureRandom()
        private val encoder = Base64.getEncoder()
        private val decoder = Base64.getDecoder()

        /** A fixed salt, so [dummy] is deterministic across processes. */
        private val DUMMY_SALT: ByteArray = "grom-dummy-salt!".toByteArray(Charsets.UTF_8)

        /** Not a credential: it only exists to make [dummy] a well-formed hash. */
        private const val DUMMY_PASSWORD = "grom-dummy-password-not-a-credential"

        private val dummyHash: PasswordHash by lazy {
            PasswordHash(encode(ITERATIONS, DUMMY_SALT, derive(DUMMY_PASSWORD, DUMMY_SALT, ITERATIONS)))
        }

        /**
         * Hashes [password] with a fresh 16-byte salt and a 32-byte derived key.
         *
         * @throws IllegalArgumentException when the password is too short or the
         * iteration count is outside the accepted range.
         */
        fun create(password: String, iterations: Int = ITERATIONS): PasswordHash {
            require(password.length >= MIN_LENGTH) {
                "password must be at least $MIN_LENGTH characters"
            }
            require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) {
                "iterations must be between $MIN_ITERATIONS and $MAX_ITERATIONS"
            }
            val salt = ByteArray(SALT_BYTES)
            random.nextBytes(salt)
            val hash = derive(password, salt, iterations)
            return PasswordHash(encode(iterations, salt, hash))
        }

        /**
         * Strictly parses an encoded hash. Returns `null` unless the value is
         * exactly four `$`-separated fields with the expected scheme, a sane
         * iteration count, a 16..64-byte salt and a 32..64-byte hash, all in
         * valid standard Base64.
         */
        fun decode(encoded: String?): PasswordHash? {
            if (encoded == null || parse(encoded) == null) return null
            return PasswordHash(encoded)
        }

        fun isEncoded(value: String?): Boolean = decode(value) != null

        /**
         * A fixed, valid hash used to burn the same CPU time as a real
         * verification when no password is configured, so a login attempt does
         * not reveal whether password auth is enabled.
         */
        fun dummy(): PasswordHash = dummyHash

        internal fun parse(encoded: String?): Parsed? {
            if (encoded == null || encoded.isEmpty() || encoded.length > MAX_ENCODED_LENGTH) return null
            val fields = encoded.split('$', limit = 5)
            if (fields.size != 4) return null
            if (fields[0] != SCHEME) return null

            val iterationsText = fields[1]
            if (iterationsText.isEmpty() ||
                iterationsText.length > 9 ||
                !iterationsText.all { it in '0'..'9' }
            ) {
                return null
            }
            val iterations = iterationsText.toIntOrNull() ?: return null
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null

            val salt = decodeBase64(fields[2]) ?: return null
            if (salt.size !in MIN_SALT_BYTES..MAX_SALT_BYTES) return null

            val hash = decodeBase64(fields[3]) ?: return null
            if (hash.size !in MIN_HASH_BYTES..MAX_HASH_BYTES) return null

            return Parsed(iterations, salt, hash)
        }

        private fun encode(iterations: Int, salt: ByteArray, hash: ByteArray): String =
            listOf(
                SCHEME,
                iterations.toString(),
                encoder.encodeToString(salt),
                encoder.encodeToString(hash)
            ).joinToString("\$")

        private fun decodeBase64(value: String): ByteArray? {
            if (value.isEmpty()) return null
            return runCatching { decoder.decode(value) }.getOrNull()
        }

        private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BYTES * 8)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}
