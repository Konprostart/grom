// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The peer client (D74, D75).
 *
 * A probe is the only thing that can change a peer lamp, so every answer is
 * mapped to a stable code and a device that was reset is detected instead of
 * being silently treated as the same machine.
 */
class PeerClientTest {

    private val seed = Ed25519.generatePrivateSeed()
    private val publicKey = Ed25519.derivePublicKey(seed)
    private val nodeId = Ed25519.keyId(publicKey)

    private fun peer() = PeerRecord(
        nodeId = nodeId,
        publicKey = Base64.getEncoder().encodeToString(publicKey),
        label = "Pixel 4a",
        address = "http://10.0.0.7:47002",
        grantedByMe = setOf(PeerScope.STATUS_READ),
        grantedByPeer = setOf(PeerScope.STATUS_READ),
        pairedAtMs = 1L
    )

    private class FakeTransport(
        var answer: () -> HttpJsonResponse
    ) : JsonTransport {
        var calls = 0
        var lastHeaders: Map<String, String> = emptyMap()
        var lastUrl: String = ""
        var lastBody: String? = null

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int
        ): HttpJsonResponse {
            calls += 1
            lastHeaders = headers
            lastUrl = url
            lastBody = body
            return answer()
        }
    }

    @Test
    fun `a 200 that still reports the paired node is alive`() {
        val transport = FakeTransport {
            HttpJsonResponse(200, """{"ok":true,"fleet":{"nodeId":"$nodeId","label":"Pixel 4a"}}""")
        }
        val client = PeerClient(transport = transport, clockSeconds = { 1_000L })
        assertEquals(PeerClient.Probe.Ok, client.probe(peer(), seed))

        // The request was signed with the shared canonical format.
        assertEquals(nodeId, transport.lastHeaders["x-grom-key"])
        assertEquals("1000", transport.lastHeaders["x-grom-timestamp"])
        assertNotNull(transport.lastHeaders["x-grom-nonce"])
        assertNotNull(transport.lastHeaders["x-grom-signature"])
        assertEquals("http://10.0.0.7:47002/v1/status", transport.lastUrl)
    }

    @Test
    fun `a device that reports another node id is detected`() {
        val transport = FakeTransport {
            HttpJsonResponse(200, """{"ok":true,"fleet":{"nodeId":"0000000000000000"}}""")
        }
        val result = PeerClient(transport = transport).probe(peer(), seed)
        assertEquals("PEER_IDENTITY_CHANGED", (result as PeerClient.Probe.Failed).code)
    }

    @Test
    fun `a status without a fleet block is not proof of liveness`() {
        val transport = FakeTransport { HttpJsonResponse(200, """{"ok":true}""") }
        val result = PeerClient(transport = transport).probe(peer(), seed)
        assertEquals("PEER_BAD_RESPONSE", (result as PeerClient.Probe.Failed).code)
    }

    @Test
    fun `statuses map to stable codes`() {
        fun probeWith(status: Int): String {
            val transport = FakeTransport { HttpJsonResponse(status, "{}") }
            val result = PeerClient(transport = transport).probe(peer(), seed)
            return (result as PeerClient.Probe.Failed).code
        }
        assertEquals("PEER_UNAUTHORIZED", probeWith(401))
        assertEquals("PEER_SCOPE_REQUIRED", probeWith(403))
        assertEquals("PEER_REDIRECTED", probeWith(302))
        assertEquals("PEER_BAD_RESPONSE", probeWith(500))
    }

    @Test
    fun `a transport failure is unreachable and carries no address`() {
        val transport = FakeTransport { throw TransportException("transport failure") }
        val result = PeerClient(transport = transport).probe(peer(), seed)
        val code = (result as PeerClient.Probe.Failed).code
        assertEquals("PEER_UNREACHABLE", code)
        assertFalse(code.contains("http"))
        assertFalse(code.contains("10.0.0.7"))
    }

    @Test
    fun `an inbound-only grant is never dialled`() {
        val transport = FakeTransport { HttpJsonResponse(200, "{}") }
        val result = PeerClient(transport = transport).probe(peer().copy(address = ""), seed)

        // The honest state is "no address to call", not a red lamp caused by a
        // connection to nowhere (D77).
        assertEquals("PEER_NO_ADDRESS", (result as PeerClient.Probe.Failed).code)
        assertEquals("no request may be attempted", 0, transport.calls)
    }

    @Test
    fun `an identity document is parsed and checked against its key`() {
        val document = """{"nodeId":"$nodeId","publicKey":"${Base64.getEncoder().encodeToString(publicKey)}","label":"Pixel 4a"}"""
        val transport = FakeTransport { HttpJsonResponse(200, document) }
        val identity = PeerClient(transport = transport).identity("http://10.0.0.7:47002")
        assertNotNull(identity)
        assertEquals(nodeId, identity?.nodeId)
    }

    @Test
    fun `an identity whose key does not match its id is refused`() {
        val other = Ed25519.derivePublicKey(Ed25519.generatePrivateSeed())
        val document = """{"nodeId":"$nodeId","publicKey":"${Base64.getEncoder().encodeToString(other)}","label":"X"}"""
        val transport = FakeTransport { HttpJsonResponse(200, document) }
        assertNull(PeerClient(transport = transport).identity("http://10.0.0.7:47002"))
    }

    @Test
    fun `a closed pairing surface yields no identity`() {
        val transport = FakeTransport { HttpJsonResponse(404, """{"ok":false}""") }
        assertNull(PeerClient(transport = transport).identity("http://10.0.0.7:47002"))
    }

    @Test
    fun `hello and poll post JSON and surface the refusal code`() {
        val transport = FakeTransport {
            HttpJsonResponse(
                409,
                """{"ok":false,"error":{"code":"PAIR_SESSION_CLOSED","message":"closed"}}"""
            )
        }
        val client = PeerClient(transport = transport)
        val hello = client.hello("http://10.0.0.7:47002", mapOf("nodeId" to "x"))
        assertEquals(409, hello.status)
        assertEquals(
            "PAIR_SESSION_CLOSED",
            (hello.payload["error"] as Map<*, *>)["code"]
        )
        assertTrue(transport.lastBody?.contains("\"nodeId\"") == true)
    }
}
