// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {

    @Test
    fun `object with mixed values`() {
        val encoded = Json.encode(
            linkedMapOf<String, Any?>(
                "api" to 1,
                "ok" to true,
                "name" to "grom",
                "nothing" to null
            )
        )
        assertEquals("{\"api\":1,\"ok\":true,\"name\":\"grom\",\"nothing\":null}", encoded)
    }

    @Test
    fun `integral doubles lose the decimal part`() {
        assertEquals("{\"n\":2}", Json.encode(mapOf("n" to 2.0)))
    }

    @Test
    fun `non integral doubles keep it`() {
        assertEquals("{\"n\":2.5}", Json.encode(mapOf("n" to 2.5)))
    }

    @Test
    fun `strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", Json.encode("a\"b\\c\nd"))
    }

    @Test
    fun `control characters are escaped`() {
        assertEquals("\"\\u0001\"", Json.encode("\u0001"))
    }

    @Test
    fun `lists are encoded`() {
        assertEquals("[1,2,3]", Json.encode(listOf(1, 2, 3)))
        assertEquals("[]", Json.encode(emptyList<Int>()))
    }

    @Test
    fun `nested structures`() {
        val value = mapOf("entries" to listOf(mapOf("seq" to 1L), mapOf("seq" to 2L)))
        assertEquals("{\"entries\":[{\"seq\":1},{\"seq\":2}]}", Json.encode(value))
    }

    @Test
    fun `raw literals pass through`() {
        assertEquals("{\"x\":{\"y\":1}}", Json.encode(mapOf("x" to Json.Raw("{\"y\":1}"))))
    }

    @Test
    fun `nan becomes null rather than invalid json`() {
        assertEquals("{\"n\":null}", Json.encode(mapOf("n" to Double.NaN)))
    }

    @Test
    fun `string field extraction`() {
        assertEquals("ping", JsonField.string("{\"op\":\"ping\"}", "op"))
        assertEquals("p\"q", JsonField.string("{\"op\":\"p\\\"q\"}", "op"))
        assertEquals(null, JsonField.string("{}", "op"))
    }
}
