// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenTest {

    @Test
    fun `generated token has the documented shape`() {
        val token = Token.generate()
        assertEquals(Token.LENGTH, token.length)
        assertTrue(Token.hasValidShape(token))
    }

    @Test
    fun `generated tokens differ`() {
        val tokens = HashSet<String>()
        repeat(200) { tokens.add(Token.generate()) }
        assertEquals(200, tokens.size)
    }

    @Test
    fun `matches accepts the same value`() {
        val token = Token.generate()
        assertTrue(Token.matches(token, token))
    }

    @Test
    fun `matches rejects a different value`() {
        val a = Token.generate()
        val b = Token.generate()
        assertNotEquals(a, b)
        assertFalse(Token.matches(a, b))
    }

    @Test
    fun `matches rejects null and empty`() {
        assertFalse(Token.matches(null, "x"))
        assertFalse(Token.matches("x", null))
        assertFalse(Token.matches("", ""))
    }

    @Test
    fun `fingerprint is eight lowercase hex characters and stable`() {
        val token = Token.generate()
        val fingerprint = Token.fingerprint(token)
        assertEquals(8, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789abcdef" })
        assertEquals(fingerprint, Token.fingerprint(token))
        assertFalse(fingerprint.contains(token))
    }

    @Test
    fun `fingerprint differs for different tokens`() {
        assertNotEquals(Token.fingerprint(Token.generate()), Token.fingerprint(Token.generate()))
    }

    @Test
    fun `bearer header is parsed case-insensitively`() {
        val value = Token.generate()
        assertEquals(value, Token.fromHeaders("Bearer $value", null))
        assertEquals(value, Token.fromHeaders("bearer $value", null))
        assertEquals(value, Token.fromHeaders("  Bearer   $value  ", null))
    }

    @Test
    fun `x-grom-token is accepted`() {
        val value = Token.generate()
        assertEquals(value, Token.fromHeaders(null, value))
    }

    @Test
    fun `missing credentials are null`() {
        assertNull(Token.fromHeaders(null, null))
        assertNull(Token.fromHeaders("", null))
        assertNull(Token.fromHeaders("Basic abc", null))
        assertNull(Token.fromHeaders("Bearer ", null))
    }

    @Test
    fun `invalid shape is rejected`() {
        assertFalse(Token.hasValidShape(null))
        assertFalse(Token.hasValidShape("short"))
        assertFalse(Token.hasValidShape("a".repeat(43) + "!"))
        assertFalse(Token.hasValidShape("a".repeat(44)))
    }
}
