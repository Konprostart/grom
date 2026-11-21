// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

/**
 * The operator's notification settings (D65, D67).
 *
 * Only three things exist: a switch, a numeric chat id and the bot token as
 * `SecretBox` ciphertext. The token is never stored, returned or logged in
 * clear text; [NotifySettingsStore] is the only writer and it seals the value
 * before it reaches the disk.
 */
data class NotifySettings(
    val enabled: Boolean = false,
    val chatId: String = "",
    /** AES-GCM ciphertext from [app.grom.server.store.SecretBox]; never plaintext. */
    val tokenSealed: String? = null
) {

    /** A chat and a token are both required before anything can be sent. */
    fun isConfigured(): Boolean = chatId.isNotBlank() && !tokenSealed.isNullOrBlank()

    /** Nothing set at all: the section shows "not set" rather than "off". */
    fun isEmpty(): Boolean = !enabled && chatId.isBlank() && tokenSealed.isNullOrBlank()
}

/**
 * What the operator typed. A `null` [token] keeps the stored one, so the switch
 * can be toggled without resending the secret.
 */
data class NotifyDraft(
    val enabled: Boolean,
    val chatId: String,
    val token: String? = null,
    val clearToken: Boolean = false
)

/** A validation failure with a stable code the UI and the API can act on. */
data class NotifyProblem(val code: String, val message: String)

/**
 * Pure validation (no network, no Android): every rule that decides whether a
 * setting may be stored lives here so the JVM tests can exercise it directly.
 */
object NotifySettingsRules {

    /**
     * BotFather tokens look like `123456789:AAH…` — a numeric bot id, a colon,
     * and 30–60 characters of base64url. The shape is checked, not the secret:
     * Grom cannot verify a token without calling the provider.
     */
    val TOKEN_PATTERN: Regex = Regex("^\\d{5,16}:[A-Za-z0-9_-]{30,60}$")

    /**
     * Numeric only (D65 Q3): a private chat is the user's id, a group is a
     * negative id. An `@channel` user name is refused because it can silently
     * resolve to a different chat and cannot be checked on the device.
     */
    val CHAT_ID_PATTERN: Regex = Regex("^-?\\d{5,20}$")

    const val MAX_CHAT_ID_CHARS: Int = 24
    const val MAX_TOKEN_CHARS: Int = 128

    fun validate(draft: NotifyDraft): NotifyProblem? {
        val token = draft.token?.trim()
        if (token != null && token.isNotEmpty()) {
            if (token.length > MAX_TOKEN_CHARS) {
                return NotifyProblem("BAD_BOT_TOKEN", "the bot token is too long")
            }
            if (!TOKEN_PATTERN.matches(token)) {
                return NotifyProblem(
                    "BAD_BOT_TOKEN",
                    "a bot token looks like 123456789:AAH… (from @BotFather)"
                )
            }
        }
        val chatId = draft.chatId.trim()
        if (chatId.isNotEmpty()) {
            if (chatId.length > MAX_CHAT_ID_CHARS || !CHAT_ID_PATTERN.matches(chatId)) {
                return NotifyProblem(
                    "BAD_CHAT_ID",
                    "the chat id is numeric (a private chat id, or a negative group id)"
                )
            }
        }
        return null
    }

    /**
     * The switch may only be turned on when a delivery could actually happen:
     * a stored token and a valid chat id. Turning it off is always allowed.
     */
    fun validateEnabled(
        draft: NotifyDraft,
        existing: NotifySettings
    ): NotifyProblem? {
        if (!draft.enabled) return null
        val tokenWillExist = when {
            draft.clearToken -> false
            !draft.token.isNullOrBlank() -> true
            else -> !existing.tokenSealed.isNullOrBlank()
        }
        val chatWillExist = draft.chatId.trim().isNotEmpty() ||
            (draft.chatId.trim().isEmpty() && existing.chatId.isNotBlank())
        if (!tokenWillExist || !chatWillExist) {
            return NotifyProblem(
                "NOTIFY_NOT_CONFIGURED",
                "enter a bot token and a chat id before switching alerts on"
            )
        }
        return null
    }
}
