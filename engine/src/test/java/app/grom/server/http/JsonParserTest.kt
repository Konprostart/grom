// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonParserTest {

    @Test
    fun `parses objects with mixed value types`() {
        val value = JsonParser.parse(
            """{"state":"READY","api":1,"ok":true,"missing":null,"ratio":0.5}"""
        ) as Map<*, *>
        assertEquals("READY", value["state"])
        assertEquals(1L, value["api"])
        assertEquals(true, value["ok"])
        assertNull(value["missing"])
        assertEquals(0.5, value["ratio"])
    }

    @Test
    fun `parses nested arrays and objects`() {
        val value = JsonParser.parse(
            """{"assets":[{"name":"a.apk","size":10},{"name":"b.apk","size":20}]}"""
        ) as Map<*, *>
        val assets = value["assets"] as List<*>
        assertEquals(2, assets.size)
        assertEquals("b.apk", (assets[1] as Map<*, *>)["name"])
        assertEquals(20L, (assets[1] as Map<*, *>)["size"])
    }

    @Test
    fun `decodes escapes and unicode`() {
        val value = JsonParser.parse(
            """{"body":"line\nquote\"slash\\tab\tunicode\u00e9"}"""
        ) as Map<*, *>
        assertEquals("line\nquote\"slash\\tab\tunicode\u00e9", value["body"])
    }

    @Test
    fun `accepts integral numbers beyond Int range`() {
        val value = JsonParser.parse("""{"uptimeMs":9007199254740993}""") as Map<*, *>
        assertEquals(9007199254740993L, value["uptimeMs"])
    }

    @Test
    fun `rejects trailing data`() {
        assertTrue(runCatching { JsonParser.parse("""{"a":1} garbage""") }.isFailure)
    }

    @Test
    fun `rejects truncated documents`() {
        assertTrue(runCatching { JsonParser.parse("""{"a":""") }.isFailure)
        assertTrue(runCatching { JsonParser.parse("") }.isFailure)
    }

    @Test
    fun `parseObjectOrNull returns null on malformed input`() {
        assertNull(JsonParser.parseObjectOrNull("<html>not json</html>"))
        assertNull(JsonParser.parseObjectOrNull("[1,2,3]"))
    }
}
