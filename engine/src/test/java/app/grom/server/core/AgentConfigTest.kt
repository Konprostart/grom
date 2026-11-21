// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigTest {

    private val token = "A".repeat(43)

    @Test
    fun `payload is one JSON line`() {
        val payload = AgentConfig.encode(token, 47001, "info")
        assertTrue(payload.endsWith("\n"))
        assertEquals(1, payload.count { it == '\n' })
        assertTrue(payload.startsWith("{"))
        assertTrue(payload.contains("\"port\":47001"))
        assertTrue(payload.contains("\"logLevel\":\"info\""))
    }

    @Test
    fun `payload carries the token exactly once`() {
        val payload = AgentConfig.encode(token, 47001, "info")
        assertEquals(1, Regex(Regex.escape(token)).findAll(payload).count())
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        val escaped = AgentConfig.escape("a\"b\\c")
        assertEquals("a\\\"b\\\\c", escaped)
    }

    @Test
    fun `control characters are escaped`() {
        assertEquals("a\\nb", AgentConfig.escape("a\nb"))
        assertEquals("a\\u0001b", AgentConfig.escape("a\u0001b"))
    }

    @Test
    fun `oversized payload is refused`() {
        val huge = "x".repeat(AgentConfig.MAX_PAYLOAD_BYTES + 1)
        var threw = false
        try {
            AgentConfig.encode(huge, 1, "info")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `token never appears in a log-safe rendering`() {
        val payload = AgentConfig.encode(token, 47001, "info")
        val redacted = Redactor(listOf(token)).redact(payload)
        assertFalse(redacted.contains(token))
    }
}
