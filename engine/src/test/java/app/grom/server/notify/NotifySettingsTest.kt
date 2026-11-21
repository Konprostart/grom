// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification settings validation (D65, S4-R043).
 *
 * The rules are pure: no store, no transport, no Android. Anything that could
 * reach the disk is decided here first.
 */
class NotifySettingsTest {

    private val validToken = "123456789:" + "A".repeat(35)
    private val validChat = "987654321"

    @Test
    fun `a well formed token and chat id are accepted`() {
        val draft = NotifyDraft(enabled = true, chatId = validChat, token = validToken)
        assertNull(NotifySettingsRules.validate(draft))
        assertNull(NotifySettingsRules.validateEnabled(draft, NotifySettings()))
    }

    @Test
    fun `a token without the provider shape is refused`() {
        listOf(
            "not-a-token",
            "12345:tooshort",
            ":leadingcolon",
            "123456789:" + "A".repeat(100),
            "123456789:has space here 0123456789012345678901234"
        ).forEach { candidate ->
            val problem = NotifySettingsRules.validate(
                NotifyDraft(enabled = true, chatId = validChat, token = candidate)
            )
            assertEquals("token '$candidate'", "BAD_BOT_TOKEN", problem?.code)
        }
    }

    @Test
    fun `the chat id is numeric only`() {
        listOf("@mychannel", "12", "abc", "1234 5678", "12.5").forEach { candidate ->
            val problem = NotifySettingsRules.validate(
                NotifyDraft(enabled = true, chatId = candidate, token = validToken)
            )
            assertEquals("chat '$candidate'", "BAD_CHAT_ID", problem?.code)
        }
    }

    @Test
    fun `a negative group id is accepted`() {
        val draft = NotifyDraft(enabled = true, chatId = "-1001234567890", token = validToken)
        assertNull(NotifySettingsRules.validate(draft))
    }

    @Test
    fun `switching on without a token or a chat is refused`() {
        assertEquals(
            "NOTIFY_NOT_CONFIGURED",
            NotifySettingsRules.validateEnabled(
                NotifyDraft(enabled = true, chatId = "", token = null),
                NotifySettings()
            )?.code
        )
        assertEquals(
            "NOTIFY_NOT_CONFIGURED",
            NotifySettingsRules.validateEnabled(
                NotifyDraft(enabled = true, chatId = validChat, token = null),
                NotifySettings()
            )?.code
        )
    }

    @Test
    fun `switching on with a stored token and a typed chat is allowed`() {
        val existing = NotifySettings(enabled = false, chatId = "", tokenSealed = "sealed")
        assertNull(
            NotifySettingsRules.validateEnabled(
                NotifyDraft(enabled = true, chatId = validChat, token = null),
                existing
            )
        )
    }

    @Test
    fun `an explicit clear cannot be switched on at the same time`() {
        val existing = NotifySettings(enabled = false, chatId = validChat, tokenSealed = "sealed")
        assertEquals(
            "NOTIFY_NOT_CONFIGURED",
            NotifySettingsRules.validateEnabled(
                NotifyDraft(enabled = true, chatId = validChat, clearToken = true),
                existing
            )?.code
        )
    }

    @Test
    fun `switching off is always allowed`() {
        assertNull(
            NotifySettingsRules.validateEnabled(
                NotifyDraft(enabled = false, chatId = "", token = null),
                NotifySettings()
            )
        )
    }

    @Test
    fun `configured needs both a chat and a token`() {
        assertFalse(NotifySettings(chatId = validChat).isConfigured())
        assertFalse(NotifySettings(tokenSealed = "sealed").isConfigured())
        assertTrue(NotifySettings(chatId = validChat, tokenSealed = "sealed").isConfigured())
    }

    @Test
    fun `an empty settings record is recognisable as empty`() {
        assertTrue(NotifySettings().isEmpty())
        assertFalse(NotifySettings(enabled = true).isEmpty())
        assertFalse(NotifySettings(chatId = validChat).isEmpty())
        assertFalse(NotifySettings(tokenSealed = "sealed").isEmpty())
    }
}
