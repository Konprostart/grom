// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import app.grom.server.superserver.SuperAuth
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * The joining side of pairing and the fleet projections (D70-D75).
 *
 * A fake transport plays the device that shows the code, with a real Ed25519
 * key, so the acceptance signature and the fingerprint confirmation are
 * exercised exactly as they run on a phone.
 */
class FleetServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext.reversed()

        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:")
                .takeIf { sealed.startsWith("sealed:") }
                ?.reversed()
    }

    /** Plays the operator device that opened a session and then approved. */
    private class PairingPeer(
        val seed: ByteArray,
        val publicKey: ByteArray,
        val nodeId: String,
        val code: String,
        var approvalScopes: List<String> = listOf("config.read", "status.read"),
        var signWithWrongKey: Boolean = false
    ) : JsonTransport {

        private var nodeIdB: String = ""
        private var codeHash: String = ""
        private val pendingId = "pending-1"

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int
        ): HttpJsonResponse {
            return when {
                url.endsWith(PeerClient.PAIR_IDENTITY) -> HttpJsonResponse(
                    200,
                    """{"nodeId":"$nodeId","publicKey":"${
                        Base64.getEncoder().encodeToString(publicKey)
                    }","label":"Phone A"}"""
                )

                url.endsWith(PeerClient.PAIR_HELLO) -> {
                    val parsed = JsonParser.parseObjectOrNull(body.orEmpty()) ?: return bad()
                    nodeIdB = parsed["nodeId"] as String
                    codeHash = parsed["codeHash"] as String
                    HttpJsonResponse(
                        200,
                        """{"ok":true,"state":"pending","pendingId":"$pendingId"}"""
                    )
                }

                url.endsWith(PeerClient.PAIR_POLL) -> {
                    val accept = PairingProtocol.acceptString(
                        nodeId,
                        nodeIdB,
                        codeHash,
                        approvalScopes
                    )
                    val signingKey = if (signWithWrongKey) {
                        Ed25519.generatePrivateSeed()
                    } else {
                        seed
                    }
                    val signature = Base64.getEncoder().encodeToString(
                        Ed25519.sign(signingKey, accept.toByteArray())
                    )
                    HttpJsonResponse(
                        200,
                        """{"ok":true,"state":"accepted","nodeId":"$nodeId","label":"Phone A",""" +
                            """"fingerprint":"${NodeIdentity.formatFingerprint(nodeId)}",""" +
                            """"grantedScopes":${approvalScopes.joinToString(",", "[", "]") { "\"$it\"" }},""" +
                            """"signature":"$signature"}"""
                    )
                }

                else -> bad()
            }
        }

        private fun bad(): HttpJsonResponse =
            HttpJsonResponse(404, """{"ok":false,"error":{"code":"PAIR_UNKNOWN"}}""")
    }

    private fun service(
        peer: JsonTransport
    ): Pair<FleetService, Pair<NodeIdentityStore, PeerStore>> {
        val identityStore = NodeIdentityStore(
            File(temp.root, NodeIdentityStore.FILE_NAME),
            ReversingBox()
        )
        val peerStore = PeerStore(File(temp.root, PeerStore.FILE_NAME))
        val fleet = FleetService(
            identityStore = identityStore,
            store = peerStore,
            client = PeerClient(transport = peer),
            defaultLabel = { "Phone B" },
            localPort = { 47002 },
            joinPollIntervalMs = 0
        )
        return fleet to (identityStore to peerStore)
    }

    private fun remote(): Triple<ByteArray, ByteArray, String> {
        val seed = Ed25519.generatePrivateSeed()
        val publicKey = Ed25519.derivePublicKey(seed)
        return Triple(seed, publicKey, Ed25519.keyId(publicKey))
    }

    @Test
    fun `join verifies the acceptance and confirm stores the peer`() {
        val (seedA, publicA, nodeA) = remote()
        val code = "ABCD1234"
        val peer = PairingPeer(seedA, publicA, nodeA, code)
        val (fleet, stores) = service(peer)
        val identityStore = stores.first
        val peerStore = stores.second

        val joined = JsonParser.parseObjectOrNull(
            fleet.joinJson("""{"address":"10.0.0.7:47002","code":"$code"}""")
        )
        assertEquals(true, joined?.get("ok"))
        val joinedPeer = joined?.get("peer") as Map<*, *>
        assertEquals(nodeA, joinedPeer["nodeId"])
        assertEquals(NodeIdentity.formatFingerprint(nodeA), joinedPeer["fingerprint"])
        // Nothing is stored before the fingerprint is confirmed on this screen.
        assertTrue(peerStore.list().isEmpty())

        val confirmed = JsonParser.parseObjectOrNull(
            fleet.confirmJson("""{"nodeId":"$nodeA"}""")
        )
        assertEquals(true, confirmed?.get("ok"))
        val stored = peerStore.find(nodeA)
        assertNotNull(stored)
        assertEquals("http://10.0.0.7:47002", stored?.address)
        assertEquals(setOf(PeerScope.STATUS_READ, PeerScope.CONFIG_READ), stored?.grantedByMe)
        assertEquals("unknown", stored?.state(1L))

        val principal = fleet.principalOf(nodeA)
        assertTrue(principal is SuperAuth.Principal.Peer)
        assertEquals(
            setOf(PeerScope.STATUS_READ, PeerScope.CONFIG_READ),
            (principal as SuperAuth.Principal.Peer).scopes
        )
        assertEquals(nodeA, fleet.peerKeys().keys.single())
        assertTrue(identityStore.identity()?.let { it.nodeId != nodeA } == true)
        assertEquals(SuperAuth.Principal.Operator, fleet.principalOf("operator-key"))
    }

    @Test
    fun `an acceptance signed by the wrong key is refused`() {
        val (seedA, publicA, nodeA) = remote()
        val peer = PairingPeer(seedA, publicA, nodeA, "ABCD1234", signWithWrongKey = true)
        val (fleet, stores) = service(peer)
        val failure = runCatching {
            fleet.joinJson("""{"address":"10.0.0.7:47002","code":"ABCD1234"}""")
        }.exceptionOrNull()
        assertEquals("PAIR_BAD_SIGNATURE", (failure as ApiException).code)
        assertTrue(stores.second.list().isEmpty())
    }

    @Test
    fun `a malformed code or address is refused without a network call`() {
        val (seedA, publicA, nodeA) = remote()
        val peer = PairingPeer(seedA, publicA, nodeA, "ABCD1234")
        val (fleet, _) = service(peer)
        val badCode = runCatching {
            fleet.joinJson("""{"address":"10.0.0.7:47002","code":"short"}""")
        }.exceptionOrNull()
        assertEquals("BAD_PAIR_CODE", (badCode as ApiException).code)
        val badAddress = runCatching {
            fleet.joinJson("""{"address":"ftp://x:1","code":"ABCD1234"}""")
        }.exceptionOrNull()
        assertEquals("BAD_PEER_ADDRESS", (badAddress as ApiException).code)
    }

    @Test
    fun `an unreachable device is reported as unreachable`() {
        val transport = object : JsonTransport {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?,
                timeoutMs: Int
            ): HttpJsonResponse = HttpJsonResponse(404, "{}")
        }
        val (fleet, _) = service(transport)
        val failure = runCatching {
            fleet.joinJson("""{"address":"10.0.0.7:47002","code":"ABCD1234"}""")
        }.exceptionOrNull()
        assertEquals("PAIR_UNREACHABLE", (failure as ApiException).code)
    }

    @Test
    fun `a reset identity forgets every peer and requires the word RESET`() {
        val (seedA, publicA, nodeA) = remote()
        val (fleet, stores) = service(PairingPeer(seedA, publicA, nodeA, "ABCD1234"))
        stores.second.add(
            PeerRecord(
                nodeId = nodeA,
                publicKey = Base64.getEncoder().encodeToString(publicA),
                label = "Phone A",
                address = "http://10.0.0.7:47002",
                grantedByMe = setOf(PeerScope.STATUS_READ),
                grantedByPeer = setOf(PeerScope.STATUS_READ),
                pairedAtMs = 1L
            )
        )
        val before = fleet.identity()
        val refused = runCatching { fleet.resetIdentity("reset") }.exceptionOrNull()
        assertEquals("RESET_CONFIRMATION_REQUIRED", (refused as ApiException).code)
        assertTrue(stores.second.list().size == 1)

        fleet.resetIdentity("RESET")
        assertTrue(stores.second.list().isEmpty())
        assertTrue(before.nodeId != fleet.identity().nodeId)
    }

    @Test
    fun `the public status carries only a name and never an address`() {
        val (seedA, publicA, nodeA) = remote()
        val (fleet, _) = service(PairingPeer(seedA, publicA, nodeA, "ABCD1234"))
        val document = fleet.statusDocument()
        val identity = fleet.identity()
        assertEquals(identity.nodeId, document["nodeId"])
        assertEquals(identity.label, document["label"])
        assertEquals(setOf("nodeId", "label"), document.keys)
        assertFalse(document.toString().contains("http://"))
    }

    @Test
    fun `a peer with no scopes is not a principal and the roster keeps addresses`() {
        val (seedA, publicA, nodeA) = remote()
        val (fleet, stores) = service(PairingPeer(seedA, publicA, nodeA, "ABCD1234"))
        stores.second.add(
            PeerRecord(
                nodeId = nodeA,
                publicKey = Base64.getEncoder().encodeToString(publicA),
                label = "Phone A",
                address = "http://10.0.0.7:47002",
                grantedByMe = setOf(PeerScope.STATUS_READ),
                grantedByPeer = emptySet(),
                pairedAtMs = 1L
            )
        )
        val peers = JsonParser.parseObjectOrNull(fleet.peersJson())
        val row = (peers?.get("peers") as List<*>).single() as Map<*, *>
        assertEquals("http://10.0.0.7:47002", row["address"])
        assertNull(row["secret"])
        assertTrue(fleet.refreshJson().contains("\"ok\":true"))
    }
}
