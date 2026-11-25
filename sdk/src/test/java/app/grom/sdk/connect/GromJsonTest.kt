// SPDX-License-Identifier: Apache-2.0
package app.grom.sdk.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The SDK's dependency-free JSON reader/writer (D93). */
class GromJsonTest {

    @Test
    fun `round trips the shapes grom actually sends`() {
        val value = linkedMapOf<String, Any?>(
            "ok" to true,
            "api" to 1,
            "null" to null,
            "text" to "quote \" backslash \\ newline \n tab \t",
            "unicode" to "ümlaut — \u2603",
            "list" to listOf(1, 2L, 3.5, false, "x"),
            "nested" to mapOf("a" to listOf(mapOf("b" to 1)))
        )
        val encoded = GromJson.encode(value)
        @Suppress("UNCHECKED_CAST")
        val decoded = GromJson.parse(encoded) as Map<String, Any?>
        assertEquals(true, decoded["ok"])
        assertEquals(1L, decoded["api"])
        assertNull(decoded["null"])
        assertEquals("quote \" backslash \\ newline \n tab \t", decoded["text"])
        assertEquals("ümlaut — \u2603", decoded["unicode"])
        assertEquals(5, (decoded["list"] as List<*>).size)
        assertEquals(3.5, (decoded["list"] as List<*>)[2])
        assertEquals(1L, (((decoded["nested"] as Map<*, *>)["a"] as List<*>)[0] as Map<*, *>)["b"])
    }

    @Test
    fun `escapes every control character it writes`() {
        val encoded = GromJson.encode(mapOf("k" to "\u0001\u001f"))
        assertEquals("{\"k\":\"\\u0001\\u001f\"}", encoded)
        @Suppress("UNCHECKED_CAST")
        val decoded = GromJson.parse(encoded) as Map<String, Any?>
        assertEquals("\u0001\u001f", decoded["k"])
    }

    @Test
    fun `reads integers as longs and decimals as doubles`() {
        assertEquals(42L, GromJson.parse("42"))
        assertEquals(-7L, GromJson.parse("-7"))
        assertEquals(1.0e3, GromJson.parse("1.0e3"))
    }

    @Test
    fun `refuses trailing data and malformed input`() {
        for (bad in listOf("{} x", "{", "{\"a\":}", "[1,]", "\"abc", "01", "tru")) {
            try {
                GromJson.parse(bad)
                fail("expected a refusal for: $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `bounds the depth so a hostile server cannot exhaust the stack`() {
        val deep = "[".repeat(GromJson.MAX_DEPTH + 2) + "]".repeat(GromJson.MAX_DEPTH + 2)
        try {
            GromJson.parse(deep)
            fail("expected the depth cap to refuse the document")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `bounds the input size`() {
        val huge = "\"" + "x".repeat(GromJson.MAX_INPUT_CHARS) + "\""
        try {
            GromJson.parse(huge)
            fail("expected the size cap to refuse the document")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `parseObject returns null for a non-object`() {
        assertNull(GromJson.parseObject("[1,2]"))
        assertEquals(emptyMap<String, Any?>(), GromJson.parseObject("{}"))
    }

    @Test
    fun `does not write a non-finite number`() {
        try {
            GromJson.encode(mapOf("x" to Double.NaN))
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
