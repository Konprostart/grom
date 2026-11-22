// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.cluster.PeerScope
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.NonceCache
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Principal mapping on the data plane (D73).
 *
 * A registered key stays an operator unless the host explicitly maps it to a
 * paired device. That default is what keeps every existing client working
 * exactly as before.
 */
class SuperAuthPrincipalTest {

    private val now = 1_700_000_000L
    private val operatorSeed = Ed25519.generatePrivateSeed()
    private val operatorKey = Ed25519.derivePublicKey(operatorSeed)
    private val operatorId = Ed25519.keyId(operatorKey)

    private val peerSeed = Ed25519.generatePrivateSeed()
    private val peerKey = Ed25519.derivePublicKey(peerSeed)
    private val peerId = Ed25519.keyId(peerKey)

    private fun auth(principalOf: (String) -> SuperAuth.Principal): SuperAuth {
        val keys = mapOf(operatorId to operatorKey, peerId to peerKey)
        val signer = RequestSigner(
            keyLookup = { keys[it] },
            clock = { now },
            nonceCache = NonceCache(clock = { now * 1_000 })
        )
        return SuperAuth(
            config = SuperServerConfig.defaults().copy(auth = AuthMode.KEY),
            passwords = null,
            sessions = SessionStore(),
            requestLimiter = RateLimiter(),
            loginLimiter = LoginLimiter(),
            signer = signer,
            principalOf = principalOf
        )
    }

    private fun headers(seed: ByteArray, method: String, path: String, body: String, nonce: String): Map<String, String> {
        val key = Ed25519.derivePublicKey(seed)
        val canonical = RequestSigner.canonicalString(method, path, body, now.toString(), nonce)
        return mapOf(
            RequestSigner.HEADER_KEY to Ed25519.keyId(key),
            RequestSigner.HEADER_TIMESTAMP to now.toString(),
            RequestSigner.HEADER_NONCE to nonce,
            RequestSigner.HEADER_SIGNATURE to Base64.getEncoder()
                .encodeToString(Ed25519.sign(seed, canonical.toByteArray()))
        )
    }

    private fun authenticate(
        auth: SuperAuth,
        seed: ByteArray,
        nonce: String
    ): SuperAuth.Outcome = auth.authenticate("GET", "/v1/status", "") { name ->
        headers(seed, "GET", "/v1/status", "", nonce)[name]
    }

    @Test
    fun `without a mapping every registered key is an operator`() {
        val outcome = authenticate(auth { SuperAuth.Principal.Operator }, operatorSeed, "nonce0001")
        assertTrue(outcome is SuperAuth.Outcome.Authenticated)
        assertEquals(
            SuperAuth.Principal.Operator,
            (outcome as SuperAuth.Outcome.Authenticated).principal
        )
    }

    @Test
    fun `a mapped key becomes a peer with only its scopes`() {
        val outcome = authenticate(
            auth { id ->
                if (id == peerId) {
                    SuperAuth.Principal.Peer(peerId, setOf(PeerScope.STATUS_READ))
                } else {
                    SuperAuth.Principal.Operator
                }
            },
            peerSeed,
            "nonce0002"
        )
        val principal = (outcome as SuperAuth.Outcome.Authenticated).principal
        assertTrue(principal is SuperAuth.Principal.Peer)
        assertEquals(
            setOf(PeerScope.STATUS_READ),
            (principal as SuperAuth.Principal.Peer).scopes
        )
        assertEquals(peerId, principal.nodeId)
    }

    @Test
    fun `a bad signature is still rejected before any principal exists`() {
        val auth = auth { SuperAuth.Principal.Operator }
        val outcome = auth.authenticate("GET", "/v1/status", "") { name ->
            val valid = headers(operatorSeed, "GET", "/v1/status", "", "nonce0003")
            if (name == RequestSigner.HEADER_SIGNATURE) "AAAA" else valid[name]
        }
        assertTrue(outcome is SuperAuth.Outcome.Rejected)
        assertEquals(401, (outcome as SuperAuth.Outcome.Rejected).status)
    }
}
