// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.cluster.PairingEndpoint
import app.grom.server.cluster.PeerScope
import app.grom.server.cluster.PeerStore
import app.grom.server.cluster.NodeIdentityStore
import app.grom.server.http.HttpRequest
import app.grom.server.store.SecretBox
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.NonceCache
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * Scope enforcement and the pairing surface on the data plane (D73, D71).
 *
 * No socket is opened: the router is a pure function over an [HttpRequest], so
 * every rule is asserted directly.
 */
class ClusterRouterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext.reversed()

        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:")
                .takeIf { sealed.startsWith("sealed:") }
                ?.reversed()
    }

    private val now = 1_700_000_000L
    private val operatorSeed = Ed25519.generatePrivateSeed()
    private val operatorKey = Ed25519.derivePublicKey(operatorSeed)
    private val operatorId = Ed25519.keyId(operatorKey)
    private val peerSeed = Ed25519.generatePrivateSeed()
    private val peerKey = Ed25519.derivePublicKey(peerSeed)
    private val peerId = Ed25519.keyId(peerKey)

    private val identityStore by lazy {
        NodeIdentityStore(File(temp.root, NodeIdentityStore.FILE_NAME), ReversingBox())
    }
    private val peers by lazy { PeerStore(File(temp.root, PeerStore.FILE_NAME)) }
    private val pairing by lazy {
        PairingEndpoint(
            identity = { identityStore.loadOrCreate("Phone A") },
            seed = { identityStore.seed() },
            peers = peers,
            clock = { now }
        )
    }

    private fun buildRouter(): SuperRouter {
        val config = SuperServerConfig.defaults().copy(auth = AuthMode.KEY)
        val keys = mapOf(operatorId to operatorKey, peerId to peerKey)
        val signer = RequestSigner(
            keyLookup = { keys[it] },
            clock = { now },
            nonceCache = NonceCache(clock = { now * 1_000 })
        )
        val auth = SuperAuth(
            config = config,
            passwords = null,
            sessions = SessionStore(),
            requestLimiter = RateLimiter(),
            loginLimiter = LoginLimiter(),
            signer = signer,
            principalOf = { id ->
                if (id == peerId) {
                    SuperAuth.Principal.Peer(peerId, setOf(PeerScope.STATUS_READ))
                } else {
                    SuperAuth.Principal.Operator
                }
            }
        )
        return SuperRouter(
            config = config,
            auth = auth,
            plugins = PluginRegistry(emptyList()),
            events = EventBus(),
            uptimeMs = { 1L },
            serverStatus = { mapOf("fleet" to mapOf("nodeId" to "0123456789abcdef")) },
            pairing = pairing,
            configExport = { """{"source":"grom","fleet":{"nodeId":"0123456789abcdef"}}""" }
        )
    }

    private fun signed(
        seed: ByteArray,
        path: String,
        nonce: String,
        method: String = "GET",
        body: String = ""
    ): HttpRequest {
        val key = Ed25519.derivePublicKey(seed)
        val canonical = RequestSigner.canonicalString(method, path, body, now.toString(), nonce)
        val headers = mapOf(
            RequestSigner.HEADER_KEY to Ed25519.keyId(key),
            RequestSigner.HEADER_TIMESTAMP to now.toString(),
            RequestSigner.HEADER_NONCE to nonce,
            RequestSigner.HEADER_SIGNATURE to Base64.getEncoder()
                .encodeToString(Ed25519.sign(seed, canonical.toByteArray()))
        )
        return HttpRequest(method, path, emptyMap(), headers, "HTTP/1.1", body)
    }

    private fun plain(path: String, method: String = "GET", body: String = ""): HttpRequest =
        HttpRequest(method, path, emptyMap(), emptyMap(), "HTTP/1.1", body)

    private fun body(response: SuperResponse): Map<String, Any?> {
        val json = response as SuperResponse.Json
        @Suppress("UNCHECKED_CAST")
        return app.grom.server.http.JsonParser.parseObjectOrNull(json.body) ?: emptyMap()
    }

    private fun code(response: SuperResponse): String {
        @Suppress("UNCHECKED_CAST")
        val error = body(response)["error"] as? Map<String, Any?> ?: return ""
        return error["code"] as? String ?: ""
    }

    private val router by lazy { buildRouter() }

    @Test
    fun `health stays unauthenticated and carries no fleet roster`() {
        val response = router.handle(plain("/v1/health"), "127.0.0.1")
        assertEquals(200, (response as SuperResponse.Json).status)
        assertFalse(response.body.contains("fleet"))
        assertFalse(response.body.contains("nodeId"))
    }

    @Test
    fun `a peer may read status but not the config or the plugin surface`() {
        assertEquals(
            200,
            (router.handle(signed(peerSeed, "/v1/status", "nonce1001"), "10.0.0.7") as SuperResponse.Json).status
        )
        val config = router.handle(signed(peerSeed, "/v1/config", "nonce1002"), "10.0.0.7")
        assertEquals(403, (config as SuperResponse.Json).status)
        assertEquals("SCOPE_REQUIRED", code(config))

        val plugins = router.handle(signed(peerSeed, "/v1/plugins", "nonce1003"), "10.0.0.7")
        assertEquals(403, (plugins as SuperResponse.Json).status)
    }

    @Test
    fun `an operator key reads the sanitised config`() {
        val response = router.handle(
            signed(operatorSeed, "/v1/config", "nonce1004"),
            "10.0.0.7"
        ) as SuperResponse.Json
        assertEquals(200, response.status)
        assertTrue(response.body.contains("\"source\":\"grom\""))
    }

    @Test
    fun `the pairing identity route is closed until a session is opened`() {
        val closed = router.handle(plain("/v1/pair/identity"), "10.0.0.7")
        assertEquals(404, (closed as SuperResponse.Json).status)
        assertEquals("PAIR_SESSION_CLOSED", code(closed))

        pairing.start()
        val open = router.handle(plain("/v1/pair/identity"), "10.0.0.7")
        assertEquals(200, (open as SuperResponse.Json).status)
        assertEquals(identityStore.identity()?.nodeId, body(open)["nodeId"])
    }

    @Test
    fun `a hello with a valid signature is accepted as pending`() {
        val opened = pairing.start()
        val code = opened["code"] as String
        val self = identityStore.loadOrCreate("Phone A")
        val seedB = Ed25519.generatePrivateSeed()
        val publicB = Ed25519.derivePublicKey(seedB)
        val nodeB = Ed25519.keyId(publicB)
        val codeHash = app.grom.server.cluster.PairingProtocol.computeCodeHash(code, nodeB)
        val hello = app.grom.server.cluster.PairingProtocol.helloString(
            self.nodeId,
            nodeB,
            codeHash
        )
        val payload = app.grom.server.http.Json.encode(
            mapOf(
                "nodeId" to nodeB,
                "publicKey" to Base64.getEncoder().encodeToString(publicB),
                "label" to "Phone B",
                "port" to 47002,
                "codeHash" to codeHash,
                "scopes" to listOf("status.read"),
                "signature" to Base64.getEncoder().encodeToString(
                    Ed25519.sign(seedB, hello.toByteArray())
                )
            )
        )
        val response = router.handle(
            plain("/v1/pair/hello", method = "POST", body = payload),
            "10.0.0.7:5555"
        ) as SuperResponse.Json
        assertEquals(200, response.status)
        assertEquals("pending", body(response)["state"])
        assertEquals("http://10.0.0.7:47002", pairing.pendingDocument()["address"])
    }

    @Test
    fun `pairing routes refuse a non-POST body`() {
        pairing.start()
        val response = router.handle(plain("/v1/pair/hello"), "10.0.0.7")
        assertEquals(405, (response as SuperResponse.Json).status)
    }
}
