// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.http.Json
import app.grom.server.superserver.nostr.RemoteBridgeHint
import app.grom.server.superserver.plugins.lightning.ExternalNodeKind
import app.grom.server.superserver.plugins.lightning.ExternalNodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SuperStatusDocumentTest {

    private val npub =
        "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"

    private val config = SuperServerConfig(
        enabled = true,
        npub = npub,
        relays = listOf("WSS://Relay.Example"),
        remoteBridge = RemoteBridgeHint("Mostro VPS", "SSH reverse tunnel")
    )

    private fun health(document: Map<String, Any?>): Map<*, *> =
        document["lightningHealth"] as Map<*, *>

    @Test
    fun buildsTheReferenceBlock() {
        val doc = SuperStatusDocument.build(config, emptyMap())
        assertEquals(npub, doc["npub"])
        assertEquals(listOf("wss://relay.example"), doc["relays"])
        val bridge = doc["remoteBridge"] as Map<*, *>
        assertEquals("Mostro VPS", bridge["label"])
        assertEquals("SSH reverse tunnel", bridge["description"])
    }

    @Test
    fun unsetReferenceFieldsAreNull() {
        val doc = SuperStatusDocument.build(SuperServerConfig(), emptyMap())
        assertNull(doc["npub"])
        assertEquals(emptyList<String>(), doc["relays"])
        assertNull(doc["remoteBridge"])
        assertNull(doc["externalNode"])
    }

    @Test
    fun anExternalNodeIsPublishedWithoutItsEndpoint() {
        val node = ExternalNodeRef(ExternalNodeKind.LND, "VPS node", "node.example.com:10009")
        val doc = SuperStatusDocument.build(
            SuperServerConfig(lightning = LightningSettings(externalNode = node)),
            emptyMap()
        )
        val block = doc["externalNode"] as Map<*, *>
        assertEquals("lnd", block["kind"])
        assertEquals("VPS node", block["label"])
        assertEquals(true, block["configured"])
        assertFalse(block.containsKey("endpointHint"))
        assertFalse(Json.encode(doc).contains("node.example.com"))
        assertFalse(Json.encode(doc).contains("10009"))
    }

    @Test
    fun lightningReachableIsNullUntilProbed() {
        val doc = SuperStatusDocument.build(
            SuperServerConfig(),
            mapOf("configured" to true, "backend" to "lnbits")
        )
        val block = health(doc)
        assertEquals(true, block["configured"])
        assertEquals("lnbits", block["backend"])
        assertNull(block["reachable"])
        assertNull(block["error"])
    }

    @Test
    fun lightningReachableIsReportedWhenMeasured() {
        val doc = SuperStatusDocument.build(
            SuperServerConfig(),
            mapOf(
                "configured" to true,
                "backend" to "lnbits",
                "reachable" to true,
                "detail" to "ok"
            )
        )
        val block = health(doc)
        assertEquals(true, block["reachable"])
        assertEquals("ok", block["error"])
    }

    @Test
    fun theStatusBlockCarriesNoCredentialField() {
        val encoded = Json.encode(SuperStatusDocument.build(config, emptyMap()))
        assertFalse(encoded.contains("passwordHash"))
        assertFalse(encoded.contains("apiKeySealed"))
        assertFalse(encoded.contains("nsec1"))
    }
}
