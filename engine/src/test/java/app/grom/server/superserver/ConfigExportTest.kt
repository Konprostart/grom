// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.core.BindMode
import app.grom.server.http.JsonParser
import app.grom.server.superserver.nostr.RemoteBridgeHint
import app.grom.server.superserver.plugins.lightning.ExternalNodeKind
import app.grom.server.superserver.plugins.lightning.ExternalNodeRef
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
        assertEquals(true, dataPlane["enabled"])
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

    @Test
    fun exportsProjectsAndMeasuredBridgeWithoutAnyAddress() {
        val parsed = JsonParser.parseObjectOrNull(
            ConfigExport.render(
                config = config,
                lightning = mapOf("configured" to true, "reachable" to false),
                projects = listOf(
                    mapOf(
                        "id" to "my-wallet",
                        "title" to "My Wallet",
                        "version" to "1.0",
                        "capabilities" to listOf("kv"),
                        "state" to "alive",
                        "host" to "203.0.113.9",
                        "username" to "grom"
                    )
                ),
                bridge = mapOf("configured" to true, "connected" to true, "active" to 1)
            )
        )!!
        val project = (parsed["projects"] as List<*>).single() as Map<*, *>
        assertEquals("my-wallet", project["id"])
        assertEquals("alive", project["state"])

        val bridge = parsed["bridge"] as Map<*, *>
        assertEquals(true, bridge["connected"])
        assertEquals(1L, bridge["active"])

        val serialised = parsed.toString()
        assertFalse("the export must not carry an SSH address", serialised.contains("203.0.113.9"))
        // "grom" is the document's own source name, so assert on the field
        // name instead of the word: the project's host/user keys must be gone.
        assertFalse("the export must not carry an SSH user", serialised.contains("username"))
        assertFalse("the export must not carry an SSH host", serialised.contains("\"host\""))
        assertFalse(serialised.contains(sealedKey))
        assertFalse(serialised.contains(passwordHash))
    }

    @Test
    fun anExternalNodeExportsItsKindAndLabelButNeverItsEndpoint() {
        val endpointSentinel = "endpoint-sentinel.example:10009"
        val withNode = config.copy(
            lightning = config.lightning.copy(
                externalNode = ExternalNodeRef(
                    ExternalNodeKind.LND,
                    "VPS node",
                    endpointSentinel
                )
            )
        )
        val text = ConfigExport.render(withNode, emptyMap())
        val parsed = JsonParser.parseObjectOrNull(text)!!

        val lightning = parsed["lightning"] as Map<*, *>
        val node = lightning["externalNode"] as Map<*, *>
        assertEquals("lnd", node["kind"])
        assertEquals("VPS node", node["label"])
        assertFalse(node.containsKey("endpointHint"))
        assertFalse(text.contains(endpointSentinel))
        assertFalse(text.contains("endpoint-sentinel.example"))

        // The existing guarantees still hold with the new field present.
        assertFalse(text.contains(passwordHash))
        assertFalse(text.contains(sealedKey))
    }
}
