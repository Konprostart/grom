// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.util.Base64

/**
 * Ed25519 key material for the SSH bridge (D58).
 *
 * The private key never leaves the device in clear text: [generate] returns the
 * OpenSSH text once so the caller can seal it, and [publicKeyLine] produces the
 * line the operator pastes into the VPS `authorized_keys`.
 *
 * The engine supports both generated keys and an operator-imported key. Both
 * travel through the same loader ([keyPairFromPrivateText]), so there is one
 * code path and one set of failure modes.
 */
object SshKeys {

    const val KEY_TYPE: String = "ssh-ed25519"
    const val DEFAULT_COMMENT: String = "grom@android"

    private const val MAGIC = "openssh-key-v1\u0000"
    private const val PRIVATE_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val PRIVATE_END = "-----END OPENSSH PRIVATE KEY-----"
    private const val MAX_TEXT_CHARS = 16 * 1024

    /** A freshly generated pair; [privateKeyText] is shown once and then sealed. */
    data class Generated(
        val privateKeyText: String,
        val publicKeyLine: String
    )

    /**
     * Registers the CC0 Ed25519 provider MINA SSHD looks for. Idempotent and
     * safe to call from any thread.
     */
    @Synchronized
    fun ensureProvider(): Boolean {
        if (Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) == null) {
            runCatching { Security.addProvider(EdDSASecurityProvider()) }
        }
        return Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) != null
    }

    fun isSupported(): Boolean = ensureProvider() && SecurityUtils.isEDDSACurveSupported()

    /**
     * Generates an Ed25519 pair and encodes it as an unencrypted OpenSSH key.
     * Returns null when this runtime has no Ed25519 provider (the caller must
     * say so instead of pretending a key exists).
     */
    fun generate(comment: String = DEFAULT_COMMENT): Generated? {
        if (!ensureProvider()) return null
        return try {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val pair = keyPairFromSeed(seed) ?: return null
            val line = publicKeyLine(pair.public, comment) ?: return null
            val text = encodeOpenSshPrivateKey(seed, rawPublicBytes(pair.public), comment)
            Generated(privateKeyText = text, publicKeyLine = line)
        } catch (_: Exception) {
            null
        }
    }

    /** Parses OpenSSH/PEM private key text into a JCA key pair. */
    fun keyPairFromPrivateText(text: String): KeyPair? {
        if (text.isBlank() || text.length > MAX_TEXT_CHARS) return null
        if (!ensureProvider()) return null
        return try {
            val identities = SecurityUtils.loadKeyPairIdentities(
                null,
                null,
                ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
                null
            )
            identities?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** `ssh-ed25519 AAAA... comment`, or null for a type Grom cannot encode. */
    fun publicKeyLine(publicKey: PublicKey, comment: String = DEFAULT_COMMENT): String? = try {
        val base = PublicKeyEntry.toString(publicKey)
        if (base.isBlank()) null else "$base ${comment.trim()}".trim()
    } catch (_: Exception) {
        null
    }

    /**
     * OpenSSH `SHA256:...` fingerprint, the same form `ssh-keygen -lf` prints.
     * Used as the TOFU pin (D58).
     */
    fun fingerprint(publicKey: PublicKey): String = try {
        KeyUtils.getFingerPrint(publicKey)
    } catch (_: Exception) {
        // A key that cannot be fingerprinted must never match a pin.
        "SHA256:unavailable"
    }

    /** True when the text looks like a private key Grom could try to load. */
    fun looksLikePrivateKey(text: String): Boolean =
        text.contains("PRIVATE KEY") && text.length <= MAX_TEXT_CHARS

    // ------------------------------------------------------------- encoding

    /**
     * Minimal OpenSSH private key writer for an unencrypted Ed25519 key.
     *
     * Format (PROTOCOL.key): magic, cipher "none", kdf "none", one key, public
     * blob, then the private section with two identical check integers and
     * 1..n padding to an 8-byte block. This is exactly what ssh-keygen writes
     * for `-t ed25519 -N ""`.
     */
    fun encodeOpenSshPrivateKey(seed: ByteArray, publicBytes: ByteArray, comment: String): String {
        require(seed.size == 32) { "an Ed25519 seed is 32 bytes" }
        require(publicBytes.size == 32) { "an Ed25519 public key is 32 bytes" }

        val publicBlob = ByteArrayOutputStream().apply {
            writeString(KEY_TYPE.toByteArray(Charsets.US_ASCII))
            writeString(publicBytes)
        }.toByteArray()

        val check = SecureRandom().nextInt()
        val privateSection = ByteArrayOutputStream().apply {
            writeUint32(check)
            writeUint32(check)
            writeString(KEY_TYPE.toByteArray(Charsets.US_ASCII))
            writeString(publicBytes)
            writeString(seed + publicBytes)
            writeString(comment.toByteArray(Charsets.UTF_8))
            var pad = 1
            while (size() % 8 != 0) {
                write(pad)
                pad++
            }
        }.toByteArray()

        val body = ByteArrayOutputStream().apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            writeString("none".toByteArray(Charsets.US_ASCII))
            writeString("none".toByteArray(Charsets.US_ASCII))
            writeString(ByteArray(0))
            writeUint32(1)
            writeString(publicBlob)
            writeString(privateSection)
        }.toByteArray()

        val base64 = Base64.getEncoder().encodeToString(body)
        val wrapped = base64.chunked(70).joinToString("\n")
        return "$PRIVATE_BEGIN\n$wrapped\n$PRIVATE_END\n"
    }

    // ---------------------------------------------------------------- helpers

    private fun keyPairFromSeed(seed: ByteArray): KeyPair? {
        if (!ensureProvider()) return null
        return try {
            val curve = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val private = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, curve))
            val public = EdDSAPublicKey(EdDSAPublicKeySpec(private.abyte, curve))
            KeyPair(public, private)
        } catch (_: Exception) {
            null
        }
    }

    private fun rawPublicBytes(publicKey: PublicKey): ByteArray {
        // Generated keys are always Ed25519. Refusing anything else keeps the
        // encoder honest: an RSA key re-encoded as a 32-byte "Ed25519" key
        // would produce a file that no server accepts.
        require(publicKey is EdDSAPublicKey) { "the encoder writes Ed25519 keys only" }
        return publicKey.abyte
    }

    private fun ByteArrayOutputStream.writeUint32(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeString(bytes: ByteArray) {
        writeUint32(bytes.size)
        write(bytes)
    }

}
