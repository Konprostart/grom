// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    private val token = Token.generate()

    @Test
    fun `token is removed`() {
        val redactor = Redactor(listOf(token))
        val line = "authorization=$token"
        assertFalse(redactor.redact(line).contains(token))
        assertTrue(redactor.redact(line).contains(Redactor.MARKER))
    }

    @Test
    fun `bearer header value is removed even when the token is unknown`() {
        val redactor = Redactor(emptyList())
        assertEquals(
            "Authorization: Bearer ${Redactor.MARKER}",
            redactor.redact("Authorization: Bearer abc.def.ghi")
        )
    }

    @Test
    fun `empty secrets are ignored`() {
        val redactor = Redactor(listOf("", token))
        assertEquals("hello", redactor.redact("hello"))
    }

    @Test
    fun `null becomes empty`() {
        assertEquals("", Redactor().redact(null))
    }

    @Test
    fun `with adds a secret for a child scope`() {
        val base = Redactor(emptyList())
        val extended = base.with("second")
        assertEquals("a${Redactor.MARKER}b", extended.redact("asecondb"))
        assertEquals("asecondb", base.redact("asecondb"))
    }
}
