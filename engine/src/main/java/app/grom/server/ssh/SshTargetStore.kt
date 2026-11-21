// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import java.io.File

/**
 * Persistent list of operator-configured SSH targets (D58).
 *
 * Same storage discipline as every other Grom store (D40): one small JSON file,
 * owner-only permissions, write-to-temp-then-rename and a hard size cap.
 *
 * Secrets are written only as [SecretBox] ciphertext. [upsert] is the single
 * write path and it re-validates the draft, so a malformed host, an over-long
 * label or a secret that is not a key cannot reach the disk.
 */
class SshTargetStore(
    private val file: File,
    private val secretBox: SecretBox,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun list(): List<SshTarget> = load()

    @Synchronized
    fun find(id: String): SshTarget? = load().firstOrNull { it.id == id }

    /** The secret to authenticate with, decrypted for the duration of a connect. */
    @Synchronized
    fun openSecret(target: SshTarget): String? = when (target.auth) {
        SshAuth.KEY -> target.privateKeySealed?.let { secretBox.open(it) }
        SshAuth.PASSWORD -> target.passwordSealed?.let { secretBox.open(it) }
    }

    @Synchronized
    fun upsert(draft: SshTargetDraft): SshTarget {
        SshTargetRules.validate(draft)?.let { throw ApiException(400, it.code, it.message) }

        val records = load().toMutableList()
        val existing = draft.id?.let { id -> records.firstOrNull { it.id == id } }
        if (existing == null && records.size >= SshTarget.MAX_TARGETS) {
            throw ApiException(
                409,
                "TOO_MANY_TARGETS",
                "at most ${SshTarget.MAX_TARGETS} SSH targets may be saved"
            )
        }

        val id = existing?.id ?: SshTarget.newId()
        var passwordSealed = existing?.passwordSealed
        var privateKeySealed = existing?.privateKeySealed
        var publicKeyLine = existing?.publicKeyLine

        if (draft.clearSecret) {
            passwordSealed = null
            privateKeySealed = null
            publicKeyLine = null
        }

        when (draft.auth) {
            SshAuth.PASSWORD -> {
                val typed = draft.password
                if (!typed.isNullOrEmpty()) {
                    passwordSealed = secretBox.seal(typed)
                    // Keeping a stale private key would let a later auth switch
                    // resurrect a credential the operator thought they removed.
                    privateKeySealed = null
                    publicKeyLine = null
                }
            }

            SshAuth.KEY -> {
                val typed = draft.privateKey
                if (!typed.isNullOrBlank()) {
                    val pair = SshKeys.keyPairFromPrivateText(typed)
                        ?: throw ApiException(
                            400,
                            "BAD_KEY",
                            "that private key could not be parsed (OpenSSH or PEM text is required)"
                        )
                    privateKeySealed = secretBox.seal(typed)
                    publicKeyLine = draft.publicKeyLine
                        ?: SshKeys.publicKeyLine(pair.public)
                    passwordSealed = null
                }
            }
        }

        // An explicit clear is allowed to leave the target unconfigured: the
        // operator asked for the credential to be gone, and `isConfigured`
        // reports the truth. Any other save must carry a usable secret.
        if (!draft.clearSecret) {
            if (draft.auth == SshAuth.KEY && privateKeySealed.isNullOrBlank()) {
                throw ApiException(
                    400,
                    "KEY_REQUIRED",
                    "key authentication needs a generated pair or a pasted private key"
                )
            }
            if (draft.auth == SshAuth.PASSWORD && passwordSealed.isNullOrBlank()) {
                throw ApiException(
                    400,
                    "PASSWORD_REQUIRED",
                    "password authentication needs a stored password"
                )
            }
        }

        val saved = SshTarget(
            id = id,
            label = draft.label.trim(),
            host = draft.host.trim(),
            port = draft.port,
            username = draft.username.trim(),
            auth = draft.auth,
            passwordSealed = passwordSealed,
            privateKeySealed = privateKeySealed,
            publicKeyLine = publicKeyLine,
            // A changed host must not inherit the old host's pin: that would
            // silently trust a different machine.
            hostKeySha256 = existing?.hostKeySha256?.takeIf {
                existing.host.equals(draft.host.trim(), ignoreCase = true) &&
                    existing.port == draft.port
            },
            remoteForwardPort = draft.remoteForwardPort,
            enabled = draft.enabled
        )

        records.removeAll { it.id == id }
        records.add(saved)
        save(records)
        return saved
    }

    @Synchronized
    fun delete(id: String) {
        val records = load().toMutableList()
        val removed = records.removeAll { it.id == id }
        if (!removed) throw ApiException(404, "TARGET_NOT_FOUND", "no SSH target with that id")
        save(records)
    }

    /**
     * Records a TOFU pin. Only the fingerprint is stored, never the key itself.
     * A pin is never replaced while it exists (see [clearPin]).
     */
    @Synchronized
    fun pinHostKey(id: String, fingerprint: String) {
        if (!fingerprint.startsWith("SHA256:") || fingerprint.length > 128) {
            throw ApiException(400, "BAD_FINGERPRINT", "fingerprint is not a SHA256 pin")
        }
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) throw ApiException(404, "TARGET_NOT_FOUND", "no SSH target with that id")
        records[index] = records[index].copy(hostKeySha256 = fingerprint)
        save(records)
    }

    /** Explicit operator action, used after the host legitimately changed. */
    @Synchronized
    fun clearPin(id: String) {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) throw ApiException(404, "TARGET_NOT_FOUND", "no SSH target with that id")
        records[index] = records[index].copy(hostKeySha256 = null)
        save(records)
    }

    // ------------------------------------------------------------------ storage

    private fun load(): List<SshTarget> {
        lastError = null
        if (!file.isFile) return emptyList()
        return try {
            val text = file.readText()
            if (text.length > MAX_FILE_BYTES) {
                lastError = "ssh targets file is larger than $MAX_FILE_BYTES bytes"
                return emptyList()
            }
            val root = JsonParser.parseObjectOrNull(text)
            if (root == null) {
                lastError = "ssh targets file is not a JSON object"
                return emptyList()
            }
            val raw = root["targets"] as? List<*> ?: return emptyList()
            val out = ArrayList<SshTarget>(raw.size)
            for (item in raw) {
                val map = item as? Map<*, *> ?: continue
                val target = fromJson(map) ?: continue
                val problem = SshTargetRules.validateStored(target)
                if (problem != null) {
                    lastError = "target ${target.id} rejected: ${problem.code}"
                    continue
                }
                out.add(target)
            }
            out
        } catch (failure: Exception) {
            lastError = "ssh targets unreadable: ${failure.javaClass.simpleName}"
            emptyList()
        }
    }

    private fun save(records: List<SshTarget>) {
        file.parentFile?.mkdirs()
        val text = Json.encode(
            mapOf(
                "version" to 1,
                "targets" to records.map(::toJson)
            )
        )
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        harden(temp)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        harden(file)
    }

    private fun toJson(target: SshTarget): Map<String, Any?> = linkedMapOf(
        "id" to target.id,
        "label" to target.label,
        "host" to target.host,
        "port" to target.port,
        "username" to target.username,
        "auth" to target.auth.wireName,
        "passwordSealed" to target.passwordSealed,
        "privateKeySealed" to target.privateKeySealed,
        "publicKeyLine" to target.publicKeyLine,
        "hostKeySha256" to target.hostKeySha256,
        "remoteForwardPort" to target.remoteForwardPort,
        "enabled" to target.enabled,
        // Informational for the UI; liveness is always measured at connect time.
        "updatedAtMs" to clock()
    )

    private fun fromJson(raw: Map<*, *>): SshTarget? {
        val id = raw["id"] as? String ?: return null
        val label = raw["label"] as? String ?: return null
        val host = raw["host"] as? String ?: return null
        val username = raw["username"] as? String ?: return null
        val auth = SshAuth.fromWire(raw["auth"] as? String) ?: return null
        return SshTarget(
            id = id,
            label = label,
            host = host,
            port = (raw["port"] as? Number)?.toInt() ?: 22,
            username = username,
            auth = auth,
            passwordSealed = (raw["passwordSealed"] as? String)?.takeIf { it.isNotBlank() },
            privateKeySealed = (raw["privateKeySealed"] as? String)?.takeIf { it.isNotBlank() },
            publicKeyLine = (raw["publicKeyLine"] as? String)?.takeIf { it.isNotBlank() },
            hostKeySha256 = (raw["hostKeySha256"] as? String)?.takeIf { it.isNotBlank() },
            remoteForwardPort = (raw["remoteForwardPort"] as? Number)?.toInt()
                ?: SshTarget.DEFAULT_FORWARD_PORT,
            enabled = raw["enabled"] as? Boolean ?: true
        )
    }

    private fun harden(target: File) {
        runCatching { target.setReadable(false, false) }
        runCatching { target.setReadable(true, true) }
        runCatching { target.setWritable(false, false) }
        runCatching { target.setWritable(true, true) }
    }

    companion object {
        const val FILE_NAME: String = "ssh-targets.json"
        private const val MAX_FILE_BYTES: Int = 64 * 1024
    }
}
