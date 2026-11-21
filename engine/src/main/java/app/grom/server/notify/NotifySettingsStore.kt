// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import java.io.File

/**
 * Persists the operator's notification settings (D65, D67).
 *
 * Same storage discipline as every other Grom store (D40): one small JSON file,
 * owner-only permissions, write-to-temp-then-rename and a hard size cap.
 *
 * The bot token is written **only** as [SecretBox] ciphertext. The chat id is
 * stored in clear text because it is not a secret, but it is device-identifying,
 * so it is never published in `/v1/status` or in the sanitised export
 * ([NotifyStatus] is the only projection).
 */
class NotifySettingsStore(
    private val file: File,
    private val secretBox: SecretBox,
    private val maxFileBytes: Int = MAX_FILE_BYTES
) {

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun settings(): NotifySettings = load() ?: NotifySettings()

    /** The token to authenticate with, decrypted for the duration of one request. */
    @Synchronized
    fun openToken(): String? = settings().tokenSealed?.let { secretBox.open(it) }

    /**
     * The single write path. It re-validates the draft first, so a malformed
     * token or chat id can never reach the disk, and it throws a stable
     * [ApiException] the API can map to `GROM_E_*`.
     */
    @Synchronized
    fun save(draft: NotifyDraft): NotifySettings {
        val existing = settings()

        NotifySettingsRules.validate(draft)?.let { throw ApiException(400, it.code, it.message) }
        NotifySettingsRules.validateEnabled(draft, existing)?.let {
            throw ApiException(409, it.code, it.message)
        }

        var tokenSealed = existing.tokenSealed
        if (draft.clearToken) {
            tokenSealed = null
        } else {
            val typed = draft.token
            if (!typed.isNullOrBlank()) {
                tokenSealed = secretBox.seal(typed.trim())
            }
        }

        // An empty chat id means "keep the stored one"; a non-empty one replaces
        // it. There is no path that silently clears a chat id.
        val chatId = draft.chatId.trim().ifEmpty { existing.chatId }

        val saved = NotifySettings(
            enabled = draft.enabled && tokenSealed != null && chatId.isNotBlank(),
            chatId = chatId,
            tokenSealed = tokenSealed
        )
        write(saved)
        return saved
    }

    // ------------------------------------------------------------------ storage

    private fun load(): NotifySettings? {
        lastError = null
        if (!file.isFile) return null
        return try {
            val text = file.readText()
            if (text.length > maxFileBytes) {
                lastError = "notifications file is larger than $maxFileBytes bytes"
                return null
            }
            val root = JsonParser.parseObjectOrNull(text)
            if (root == null) {
                lastError = "notifications file is not a JSON object"
                return null
            }
            val chatId = (root["chatId"] as? String)?.trim().orEmpty()
            val sealed = (root["tokenSealed"] as? String)?.takeIf { it.isNotBlank() }
            NotifySettings(
                enabled = root["enabled"] as? Boolean ?: false,
                chatId = chatId,
                tokenSealed = sealed
            )
        } catch (failure: Exception) {
            lastError = "notifications unreadable: ${failure.javaClass.simpleName}"
            null
        }
    }

    private fun write(settings: NotifySettings) {
        file.parentFile?.mkdirs()
        val text = Json.encode(
            linkedMapOf(
                "version" to 1,
                "enabled" to settings.enabled,
                "chatId" to settings.chatId,
                "tokenSealed" to settings.tokenSealed
            )
        )
        if (text.length > maxFileBytes) {
            throw ApiException(
                409,
                "NOTIFY_STORE_FULL",
                "the notifications file is full (limit $maxFileBytes bytes)"
            )
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

    private fun harden(target: File) {
        runCatching { target.setReadable(false, false) }
        runCatching { target.setReadable(true, true) }
        runCatching { target.setWritable(false, false) }
        runCatching { target.setWritable(true, true) }
    }

    companion object {
        const val FILE_NAME: String = "notifications.json"

        /** Small on purpose: three fields, one of them a short ciphertext. */
        const val MAX_FILE_BYTES: Int = 64 * 1024
    }
}
