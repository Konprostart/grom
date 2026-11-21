// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from `{"status":…,"body":…}` onto [GromProject.LocalWrite].
 *
 * This is the single place where "the agent could not store anything" could be
 * mistaken for success, so every branch is asserted directly (D61).
 */
class GromProjectTest {

    private fun envelope(status: Int, body: String): String =
        """{"status":$status,"body":${quote(body)}}"""

    private fun quote(value: String): String =
        app.grom.server.http.Json.encode(listOf(value)).removeSurrounding("[", "]")

    @Test
    fun `a stored value reads back as ok`() {
        val result = GromProject.parseEnvelope(
            "kv.get",
            envelope(200, """{"op":"kv.get","ok":true,"result":{"key":"p:a:k","found":true,"value":"v"}}""")
        )
        assertEquals(GromProject.LocalWrite.Ok(found = true, value = "v"), result)
    }

    @Test
    fun `a missing value is ok but not found`() {
        val result = GromProject.parseEnvelope(
            "kv.get",
            envelope(200, """{"ok":true,"result":{"found":false,"value":null}}""")
        )
        assertEquals(GromProject.LocalWrite.Ok(found = false, value = null), result)
    }

    @Test
    fun `a put is ok even though it returns no value`() {
        val result = GromProject.parseEnvelope(
            "kv.put",
            envelope(200, """{"ok":true,"result":{"key":"p:a:k","stored":true}}""")
        )
        assertEquals(GromProject.LocalWrite.Ok(found = true, value = null), result)
    }

    @Test
    fun `missing sqlite is storage-unavailable, never success`() {
        val result = GromProject.parseEnvelope(
            "kv.put",
            envelope(503, """{"ok":false,"code":"STORAGE_UNAVAILABLE","message":"no sqlite"}""")
        )
        assertEquals(GromProject.LocalWrite.StorageUnavailable, result)
        assertTrue(result !is GromProject.LocalWrite.Ok)
    }

    @Test
    fun `an engine refusal keeps its code`() {
        val result = GromProject.parseEnvelope(
            "kv.get",
            envelope(400, """{"ok":false,"code":"PROJECT_KEY_INVALID","message":"nope"}""")
        )
        assertEquals(
            GromProject.LocalWrite.Rejected("PROJECT_KEY_INVALID", "nope"),
            result
        )
    }

    @Test
    fun `an unbound client is a rejection, not an empty success`() {
        assertEquals(
            GromProject.LocalWrite.Rejected("NOT_BOUND", "the engine did not answer"),
            GromProject.parseEnvelope("kv.get", null)
        )
    }

    @Test
    fun `a service exception maps to its stable code`() {
        assertEquals(
            GromProject.LocalWrite.Rejected("PROJECT_NOT_REGISTERED", "project 'x' has not announced"),
            GromProject.parseRefusal(
                "GROM_E_PROJECT_NOT_REGISTERED: project 'x' has not announced"
            )
        )
        assertEquals(
            GromProject.LocalWrite.Rejected("REFUSED", "odd"),
            GromProject.parseRefusal("odd")
        )
        assertEquals(
            GromProject.LocalWrite.Rejected("REFUSED", ""),
            GromProject.parseRefusal(null)
        )
    }

    @Test
    fun `the heartbeat interval is clamped`() {
        assertEquals(GromProject.MIN_HEARTBEAT_MS, GromProject.clampInterval(0))
        assertEquals(GromProject.MIN_HEARTBEAT_MS, GromProject.clampInterval(1))
        assertEquals(30_000L, GromProject.clampInterval(30_000))
        assertEquals(GromProject.MAX_HEARTBEAT_MS, GromProject.clampInterval(Long.MAX_VALUE))
    }

    @Test
    fun `the default heartbeat is the documented thirty seconds`() {
        assertEquals(30_000L, GromProject.DEFAULT_HEARTBEAT_MS)
        // Stale-after is 90 s, so three missed heartbeats are needed to go stale.
        assertEquals(
            3 * GromProject.DEFAULT_HEARTBEAT_MS,
            app.grom.server.projects.ProjectRecord.STALE_AFTER_MS
        )
    }
}
