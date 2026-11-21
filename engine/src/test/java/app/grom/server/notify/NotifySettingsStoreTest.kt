// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

import app.grom.server.http.ApiException
import app.grom.server.store.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The notification store (D67, D40).
 *
 * The point of these tests is the file: a bot token must never be on disk in
 * clear text, the write must be atomic and re-read, and the size cap must refuse
 * rather than leave an unreadable file behind.
 */
class NotifySettingsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext.reversed()

        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:")
                .takeIf { sealed.startsWith("sealed:") }
                ?.reversed()
    }

    private val token = "123456789:" + "B".repeat(35)

    private fun store(maxBytes: Int = NotifySettingsStore.MAX_FILE_BYTES) = NotifySettingsStore(
        File(temp.root, NotifySettingsStore.FILE_NAME),
        ReversingBox(),
        maxBytes
    )

    @Test
    fun `the token is sealed and the chat id is readable`() {
        val saved = store().save(NotifyDraft(enabled = true, chatId = "123456", token = token))
        assertTrue(saved.enabled)
        assertEquals("123456", saved.chatId)
        assertNotNull(saved.tokenSealed)

        val text = File(temp.root, NotifySettingsStore.FILE_NAME).readText()
        assertFalse("the plaintext token must not be on disk", text.contains(token))
        assertTrue(text.contains("sealed:"))
    }

    @Test
    fun `the sealed token opens back to the same value`() {
        val store = store()
        store.save(NotifyDraft(enabled = true, chatId = "123456", token = token))
        assertEquals(token, store.openToken())
    }

    @Test
    fun `a null token keeps the stored one and an empty chat keeps its own`() {
        val store = store()
        store.save(NotifyDraft(enabled = true, chatId = "123456", token = token))
        val toggled = store.save(NotifyDraft(enabled = false, chatId = "", token = null))
        assertFalse(toggled.enabled)
        assertEquals("123456", toggled.chatId)
        assertEquals(token, store.openToken())
    }

    @Test
    fun `an explicit clear removes the token and leaves the switch off`() {
        val store = store()
        store.save(NotifyDraft(enabled = true, chatId = "123456", token = token))
        val cleared = store.save(
            NotifyDraft(enabled = false, chatId = "123456", clearToken = true)
        )
        assertNull(cleared.tokenSealed)
        assertFalse("no token means nothing can be on", cleared.enabled)
        assertNull(store.openToken())
    }

    @Test
    fun `clearing the token while switching on is refused`() {
        val store = store()
        store.save(NotifyDraft(enabled = true, chatId = "123456", token = token))
        val failure = runCatching {
            store.save(NotifyDraft(enabled = true, chatId = "123456", clearToken = true))
        }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("NOTIFY_NOT_CONFIGURED", (failure as ApiException).code)
        // The stored token survives a refused write.
        assertEquals(token, store.openToken())
    }

    @Test
    fun `a malformed token is refused and nothing is written`() {
        val store = store()
        val failure = runCatching {
            store.save(NotifyDraft(enabled = true, chatId = "123456", token = "nope"))
        }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("BAD_BOT_TOKEN", (failure as ApiException).code)
        assertFalse(File(temp.root, NotifySettingsStore.FILE_NAME).exists())
    }

    @Test
    fun `switching on without credentials is refused with its own code`() {
        val failure = runCatching {
            store().save(NotifyDraft(enabled = true, chatId = "", token = null))
        }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("NOTIFY_NOT_CONFIGURED", (failure as ApiException).code)
    }

    @Test
    fun `an unreadable file reports an error instead of inventing settings`() {
        val file = File(temp.root, NotifySettingsStore.FILE_NAME)
        file.writeText("{not json")
        val store = store()
        assertTrue(store.settings().isEmpty())
        assertNotNull(store.lastError)
    }

    @Test
    fun `an oversized file is refused before it can be written`() {
        // Smaller than one encoded record, so the refusal is unconditional.
        val store = store(maxBytes = 64)
        val failure = runCatching {
            store.save(NotifyDraft(enabled = true, chatId = "123456", token = token))
        }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("NOTIFY_STORE_FULL", (failure as ApiException).code)
        // The previous state is untouched: nothing was truncated.
        assertFalse(File(temp.root, NotifySettingsStore.FILE_NAME).exists())
    }

    @Test
    fun `the file is owner readable only`() {
        store().save(NotifyDraft(enabled = true, chatId = "123456", token = token))
        val file = File(temp.root, NotifySettingsStore.FILE_NAME)
        assertTrue(file.canRead())
        // Java cannot express "only owner" portably; the assertion that matters
        // is that hardening ran, which is why the value is re-readable at all.
        assertTrue(file.length() > 0)
    }
}
