// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import app.grom.server.superserver.auth.Ed25519
import java.io.File
import java.util.Base64

/**
 * This device's own identity in the fleet (D70, D72).
 *
 * One Ed25519 key per device. [nodeId] is derived from the public key with
 * [Ed25519.keyId], never chosen by a caller, so an identity cannot be forged by
 * writing a file. The private seed exists only inside [NodeIdentityStore], only
 * as [SecretBox] ciphertext on disk, and is never part of any JSON document.
 *
 * There is no account and no escrow: losing the seed means losing the identity,
 * and the only recovery is to reset it and pair again with every peer. That is
 * the price of not depending on a directory.
 */
data class NodeIdentity(
    val nodeId: String,
    val publicKey: String,
    val label: String,
    val createdAtMs: Long
) {

    /** `1234-5678-9abc-def0`, for a human to compare on two screens. */
    fun fingerprint(): String = formatFingerprint(nodeId)

    /** Public projection: safe for status, export and the pairing handshake. */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "nodeId" to nodeId,
        "publicKey" to publicKey,
        "label" to label,
        "fingerprint" to fingerprint()
    )

    companion object {

        val NODE_ID_PATTERN: Regex = Regex("^[0-9a-f]{16}$")

        const val MAX_LABEL_CHARS: Int = 64

        fun formatFingerprint(nodeId: String): String {
            if (!NODE_ID_PATTERN.matches(nodeId)) return nodeId
            return buildString {
                for (index in nodeId.indices) {
                    if (index > 0 && index % 4 == 0) append('-')
                    append(nodeId[index])
                }
            }
        }

        /** Trims, drops control characters and caps the length; never empty. */
        fun normalizeLabel(raw: String?, fallback: String = DEFAULT_LABEL): String {
            val cleaned = (raw ?: "")
                .filter { !it.isISOControl() }
                .trim()
                .take(MAX_LABEL_CHARS)
            return cleaned.ifEmpty { fallback }
        }

        const val DEFAULT_LABEL: String = "Grom device"
    }
}

/**
 * Persistent store for [NodeIdentity].
 *
 * Reads are validated end to end: the public key must hash back to the stored
 * `nodeId`, and the seed, when opened, must derive that same public key. A file
 * that fails either test is reported as `IDENTITY_CORRUPT` and is **not**
 * replaced — silently minting a new identity would invalidate every pairing on
 * every other device without the operator ever noticing. Recovery is the
 * explicit `reset`, which the operator confirms with the word `RESET`.
 */
class NodeIdentityStore(
    private val file: File,
    private val secretBox: SecretBox,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxFileBytes: Int = MAX_FILE_BYTES,
    private val seedGenerator: () -> ByteArray = { Ed25519.generatePrivateSeed() }
) {

    @Volatile
    var lastError: String? = null
        private set

    private var loaded = false
    private var cachedIdentity: NodeIdentity? = null
    private var cachedSeed: ByteArray? = null

    @Synchronized
    fun identity(): NodeIdentity? {
        ensureLoaded()
        return cachedIdentity
    }

    /** A copy, so no caller can hold the seed bytes that live in this object. */
    @Synchronized
    fun seed(): ByteArray? {
        ensureLoaded()
        return cachedSeed?.copyOf()
    }

    /**
     * Returns the stored identity, or creates one on first use. A corrupt file
     * is refused rather than overwritten (D72).
     */
    @Synchronized
    fun loadOrCreate(label: String): NodeIdentity {
        ensureLoaded()
        cachedIdentity?.let { return it }
        if (file.isFile) {
            throw ApiException(
                409,
                "IDENTITY_CORRUPT",
                "the stored device identity cannot be verified; reset it explicitly"
            )
        }
        return create(NodeIdentity.normalizeLabel(label))
    }

    @Synchronized
    fun rename(label: String): NodeIdentity {
        val current = loadOrCreate(label)
        val updated = current.copy(label = NodeIdentity.normalizeLabel(label))
        save(updated, cachedSeed ?: throw ApiException(500, "IDENTITY_CORRUPT", "the seed is missing"))
        cachedIdentity = updated
        return updated
    }

    /**
     * Mints a new identity. The caller is responsible for removing every paired
     * peer: this device now looks like a different machine to all of them.
     */
    @Synchronized
    fun reset(label: String): NodeIdentity {
        cachedIdentity = null
        cachedSeed = null
        loaded = true
        if (file.isFile) file.delete()
        return create(NodeIdentity.normalizeLabel(label))
    }

    // ------------------------------------------------------------------ storage

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        load()
    }

    private fun load() {
        lastError = null
        cachedIdentity = null
        cachedSeed = null
        if (!file.isFile) return
        try {
            val text = file.readText()
            if (text.length > maxFileBytes) {
                lastError = "IDENTITY_CORRUPT"
                return
            }
            val root = JsonParser.parseObjectOrNull(text)
            if (root == null) {
                lastError = "IDENTITY_CORRUPT"
                return
            }
            val nodeId = root["nodeId"] as? String ?: return corrupt()
            val publicKeyText = root["publicKey"] as? String ?: return corrupt()
            val label = NodeIdentity.normalizeLabel(root["label"] as? String)
            val createdAt = (root["createdAtMs"] as? Number)?.toLong() ?: 0L
            val sealed = root["seedSealed"] as? String ?: return corrupt()

            val publicKey = decodeBase64(publicKeyText) ?: return corrupt()
            if (publicKey.size != Ed25519.PUBLIC_KEY_BYTES) return corrupt()
            if (Ed25519.keyId(publicKey) != nodeId) return corrupt()

            val seedText = secretBox.open(sealed) ?: return corrupt()
            val seed = decodeBase64(seedText) ?: return corrupt()
            if (seed.size != Ed25519.PRIVATE_SEED_BYTES) return corrupt()
            if (!Ed25519.derivePublicKey(seed).contentEquals(publicKey)) return corrupt()

            cachedIdentity = NodeIdentity(nodeId, publicKeyText, label, createdAt)
            cachedSeed = seed
        } catch (_: Exception) {
            corrupt()
        }
    }

    private fun corrupt() {
        lastError = "IDENTITY_CORRUPT"
        cachedIdentity = null
        cachedSeed = null
    }

    private fun create(label: String): NodeIdentity {
        val seed = seedGenerator()
        if (seed.size != Ed25519.PRIVATE_SEED_BYTES) {
            throw ApiException(500, "IDENTITY_FAILED", "the key generator returned a bad seed")
        }
        val publicKey = Ed25519.derivePublicKey(seed)
        val identity = NodeIdentity(
            nodeId = Ed25519.keyId(publicKey),
            publicKey = Base64.getEncoder().encodeToString(publicKey),
            label = label,
            createdAtMs = clock()
        )
        save(identity, seed)
        cachedIdentity = identity
        cachedSeed = seed
        return identity
    }

    private fun save(identity: NodeIdentity, seed: ByteArray) {
        file.parentFile?.mkdirs()
        val text = Json.encode(
            linkedMapOf(
                "version" to 1,
                "nodeId" to identity.nodeId,
                "publicKey" to identity.publicKey,
                "label" to identity.label,
                "createdAtMs" to identity.createdAtMs,
                // The one place a private value is written, and only sealed (D72).
                "seedSealed" to secretBox.seal(Base64.getEncoder().encodeToString(seed))
            )
        )
        if (text.length > maxFileBytes) {
            throw ApiException(500, "IDENTITY_FAILED", "the identity document is too large")
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        harden(temp)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        harden(file)
    }

    private fun decodeBase64(value: String): ByteArray? {
        if (value.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(value) }.getOrNull()
    }

    private fun harden(target: File) {
        runCatching { target.setReadable(false, false) }
        runCatching { target.setReadable(true, true) }
        runCatching { target.setWritable(false, false) }
        runCatching { target.setWritable(true, true) }
    }

    companion object {
        const val FILE_NAME: String = "identity.json"

        /** One key, one label and one sealed seed; 8 KiB is a generous bound. */
        const val MAX_FILE_BYTES: Int = 8 * 1024
    }
}
