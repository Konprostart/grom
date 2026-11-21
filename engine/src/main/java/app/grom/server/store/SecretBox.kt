// SPDX-License-Identifier: Apache-2.0
package app.grom.server.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets at rest (D44).
 *
 * Grom never keeps a wallet key, but it may keep the API credential that lets an
 * operator-configured Lightning backend be reached. That credential is a bearer
 * secret for a remote service, so it is encrypted with a key that lives in the
 * Android Keystore and never touches the filesystem.
 *
 * The interface is separate from the platform implementation so the
 * configuration format can be unit-tested on the JVM with a deterministic box.
 */
interface SecretBox {

    fun seal(plaintext: String): String

    /** Returns null when the value was sealed by another key or is corrupt. */
    fun open(sealed: String): String?
}

/**
 * AES-256-GCM under a non-exportable Android Keystore key.
 *
 * Wire format (base64): `version(1) || ivLength(1) || iv || ciphertext+tag`.
 * The version byte exists so a future rotation can be recognised instead of
 * being misparsed as the current format.
 */
class AndroidKeystoreSecretBox(
    private val alias: String = DEFAULT_ALIAS
) : SecretBox {

    override fun seal(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size in 1..255) { "unexpected GCM iv length" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val out = ByteArray(2 + iv.size + ciphertext.size)
        out[0] = FORMAT_VERSION
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(ciphertext, 0, out, 2 + iv.size, ciphertext.size)
        return Base64.getEncoder().encodeToString(out)
    }

    override fun open(sealed: String): String? {
        return try {
            val raw = Base64.getDecoder().decode(sealed)
            if (raw.size < 2 + 12 + 16) return null
            if (raw[0] != FORMAT_VERSION) return null
            val ivLength = raw[1].toInt() and 0xff
            if (ivLength < 12 || ivLength > 16) return null
            if (raw.size < 2 + ivLength + 16) return null

            val iv = raw.copyOfRange(2, 2 + ivLength)
            val ciphertext = raw.copyOfRange(2 + ivLength, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            // A rotated or deleted keystore key must degrade to "unconfigured",
            // never crash the server and never leak the wrapped bytes.
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS: String = "grom-super-secrets"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 1
    }
}
