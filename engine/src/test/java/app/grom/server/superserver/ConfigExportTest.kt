// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.core.BindMode
import app.grom.server.http.JsonParser
import app.grom.server.superserver.nostr.RemoteBridgeHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ConfigExportTest {

    private val npub =
        "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"

    private val passwordHash = "PBKDF2-SENTINEL-hash"
    private val sealedKey = "SEALED-SENTINEL-api-key"

    private val config = SuperServerConfig(
        enabled = true,
        bind = BindMode.LAN,
        port = 47_002,
        auth = AuthMode.BOTH,
        passwordHash = passwordHash,
        keys = listOf(
            ApiKeyConfig(
                keyId = "abc123",
                label = "laptop",
                publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
            )
        ),
        lightning = LightningSettings(
            kind = LightningKind.LNBITS,
            baseUrl = "https://lnbits.example",
            apiKeySealed = sealedKey
        ),
        npub = npub,
        relays = listOf("wss://relay.example"),
        remoteBridge = RemoteBridgeHint("Mostro VPS", "SSH reverse tunnel")
    )

    @Test
    fun rendersTheHandOffDocument() {
        val parsed = JsonParser.parseObjectOrNull(
            ConfigExport.render(config, mapOf("configured" to true, "reachable" to true))
        )
        assertNotNull(parsed)
        assertEquals("grom", parsed!!["source"])
        assertEquals(1L, parsed["configVersion"])
        assertEquals(npub, parsed["npub"])
        assertEquals(listOf("wss://relay.example"), parsed["relays"])

        val bridge = parsed["remoteBridge"] as Map<*, *>
        assertEquals("Mostro VPS", bridge["label"])

        val dataPlane = parsed["dataPlane"] as Map<*, *>
        assertEquals("LAN", dataPlane["bind"])
        assertEquals(47_002L, dataPlane["port"])
        assertEquals("BOTH", dataPlane["auth"])
        assertEquals("/v1/status", dataPlane["statusPath"])

        val lightning = parsed["lightning"] as Map<*, *>
        assertEquals("lnbits", lightning["kind"])
        assertEquals("https://lnbits.example", lightning["baseUrl"])
        val health = lightning["health"] as Map<*, *>
        assertEquals(true, health["configured"])
        assertEquals(true, health["reachable"])
    }

    @Test
    fun neverLeaksAStoredSecretOrKeyMaterial() {
        val out = ConfigExport.render(config, mapOf("configured" to true))
        assertFalse("password hash leaked", out.contains(passwordHash))
        assertFalse("sealed API key leaked", out.contains(sealedKey))
        assertFalse(out.contains("passwordHash"))
        assertFalse(out.contains("apiKeySealed"))
        assertFalse(out.contains("publicKey"))
        assertFalse(out.contains("keyId"))
        assertFalse(out.contains("nsec1"))
    }

    @Test
    fun unsetReferenceFieldsRenderAsNull() {
        val parsed = JsonParser.parseObjectOrNull(
            ConfigExport.render(SuperServerConfig(), emptyMap())
        )!!
        assertNull(parsed["npub"])
        assertTrue((parsed["relays"] as List<*>).isEmpty())
        assertNull(parsed["remoteBridge"])
        val health = (parsed["lightning"] as Map<*, *>)["health"] as Map<*, *>
        assertEquals(false, health["configured"])
        assertNull(health["reachable"])
    }
}
