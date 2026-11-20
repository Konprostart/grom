// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBridgeHintTest {

    @Test
    fun aPlainLabelIsAccepted() {
        assertNull(RemoteBridgeHint("Mostro VPS", "SSH reverse tunnel").validate())
    }

    @Test
    fun emptyHintsAreDetected() {
        assertTrue(RemoteBridgeHint().isEmpty())
        assertTrue(RemoteBridgeHint("  ", " ").isEmpty())
        assertTrue(!RemoteBridgeHint("x").isEmpty())
    }

    @Test
    fun normalisedTrimsBothFields() {
        assertEquals(
            RemoteBridgeHint("Mostro VPS", "tunnel"),
            RemoteBridgeHint("  Mostro VPS ", " tunnel ").normalized()
        )
    }

    @Test
    fun lengthLimitsAreEnforced() {
        assertNotNull(RemoteBridgeHint("a".repeat(RemoteBridgeHint.MAX_LABEL + 1)).validate())
        assertNotNull(
            RemoteBridgeHint("ok", "a".repeat(RemoteBridgeHint.MAX_DESCRIPTION + 1)).validate()
        )
        assertNull(RemoteBridgeHint("a".repeat(RemoteBridgeHint.MAX_LABEL)).validate())
    }

    @Test
    fun controlCharactersAreRejected() {
        assertNotNull(RemoteBridgeHint("line\nbreak").validate())
    }

    @Test
    fun secretShapesAreRefused() {
        val secrets = listOf(
            "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5",
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "password=hunter2",
            "api_key: abcdef",
            "token = deadbeef",
            "secret: xyz",
            "Authorization: Bearer abcdefghijklmnop"
        )
        for (secret in secrets) {
            assertNotNull("expected refusal for: $secret", RemoteBridgeHint(secret).validate())
        }
    }

    @Test
    fun fromJsonReadsAndDropsEmptyObjects() {
        val parsed = RemoteBridgeHint.fromJson(mapOf("label" to " VPS ", "description" to " t "))
        assertEquals(RemoteBridgeHint("VPS", "t"), parsed)
        assertNull(RemoteBridgeHint.fromJson(emptyMap<String, Any?>()))
        assertNull(RemoteBridgeHint.fromJson(null))
        assertNull(RemoteBridgeHint.fromJson("not-an-object"))
    }
}
