// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.HttpJsonClient
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import java.security.SecureRandom
import java.util.Base64

/** The public identity another device answers during pairing. */
data class RemoteIdentity(
    val nodeId: String,
    val publicKey: String,
    val label: String
)

/**
 * The only outbound path this device uses toward a peer (D75, S1-R003).
 *
 * It signs its requests with exactly the verifier's canonical format
 * ([RequestSigner.canonicalString]) and goes through the shared
 * [JsonTransport], so this class holds no socket symbol of its own. The address
 * always comes from [PeerRecord], which the operator typed during pairing; no
 * host is compiled in.
 *
 * Failures are returned as stable codes. The URL and the key never reach an
 * exception message or a stored error, because both would end up in the UI.
 */
class PeerClient(
    private val transport: JsonTransport = HttpJsonClient(),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val random: SecureRandom = SecureRandom(),
    private val timeoutMs: Int = 5_000
) {

    sealed class Probe {
        data object Ok : Probe()
        data class Failed(val code: String) : Probe()
    }

    /**
     * Fetches the peer's identity. Null means any failure; the caller reports a
     * single stable `PAIR_UNREACHABLE`, because which leg failed is not the
     * operator's problem and the detail could include the address.
     */
    fun identity(baseUrl: String): RemoteIdentity? {
        val response = try {
            transport.request("GET", "$baseUrl${PAIR_IDENTITY}", emptyMap(), null, timeoutMs)
        } catch (_: TransportException) {
            return null
        } catch (_: Exception) {
            return null
        }
        if (response.status != 200) return null
        val document = JsonParser.parseObjectOrNull(response.body) ?: return null
        val nodeId = document["nodeId"] as? String ?: return null
        val publicKey = document["publicKey"] as? String ?: return null
        if (!NodeIdentity.NODE_ID_PATTERN.matches(nodeId)) return null
        val keyBytes = runCatching { Base64.getDecoder().decode(publicKey) }.getOrNull()
            ?: return null
        if (keyBytes.size != Ed25519.PUBLIC_KEY_BYTES) return null
        if (Ed25519.keyId(keyBytes) != nodeId) return null
        return RemoteIdentity(
            nodeId = nodeId,
            publicKey = publicKey,
            label = NodeIdentity.normalizeLabel(document["label"] as? String)
        )
    }

    /**
     * Performs one measured `GET /v1/status`. `Ok` means the peer answered 200
     * *and* still reports the node id we paired with; a device that was reset
     * looks different and is never silently trusted.
     */
    fun probe(peer: PeerRecord, seed: ByteArray): Probe {
        val response = try {
            signedGet(peer.address, STATUS_PATH, seed)
        } catch (_: TransportException) {
            return Probe.Failed("PEER_UNREACHABLE")
        } catch (_: Exception) {
            return Probe.Failed("PEER_UNREACHABLE")
        }
        return when {
            response.status == 200 -> {
                val document = JsonParser.parseObjectOrNull(response.body)
                    ?: return Probe.Failed("PEER_BAD_RESPONSE")
                val fleet = document["fleet"] as? Map<*, *>
                val remoteId = fleet?.get("nodeId") as? String
                when (remoteId) {
                    // A device that was reset is a different machine until the
                    // operator pairs it again; never treat it as the old peer.
                    null -> Probe.Failed("PEER_BAD_RESPONSE")
                    peer.nodeId -> Probe.Ok
                    else -> Probe.Failed("PEER_IDENTITY_CHANGED")
                }
            }

            response.status == 401 -> Probe.Failed("PEER_UNAUTHORIZED")
            response.status == 403 -> Probe.Failed("PEER_SCOPE_REQUIRED")
            response.status in 300..399 -> Probe.Failed("PEER_REDIRECTED")
            else -> Probe.Failed("PEER_BAD_RESPONSE")
        }
    }

    /** Posts a signed hello and returns the pending id or the refusal. */
    fun hello(baseUrl: String, body: Map<String, Any?>): PairAnswer {
        val response = try {
            transport.request(
                "POST",
                "$baseUrl$PAIR_HELLO",
                mapOf("Content-Type" to "application/json"),
                Json.encode(body),
                timeoutMs
            )
        } catch (_: Exception) {
            return PairAnswer(502, PairingPort.error("PAIR_UNREACHABLE", "the peer did not answer"))
        }
        return answer(response)
    }

    /** Polls one pending pairing request. */
    fun poll(baseUrl: String, pendingId: String): PairAnswer {
        val response = try {
            transport.request(
                "POST",
                "$baseUrl$PAIR_POLL",
                mapOf("Content-Type" to "application/json"),
                Json.encode(mapOf("pendingId" to pendingId)),
                timeoutMs
            )
        } catch (_: Exception) {
            return PairAnswer(502, PairingPort.error("PAIR_UNREACHABLE", "the peer did not answer"))
        }
        return answer(response)
    }

    // ----------------------------------------------------------------- helpers

    private fun signedGet(baseUrl: String, path: String, seed: ByteArray): HttpJsonResponse {
        val publicKey = Ed25519.derivePublicKey(seed)
        val keyId = Ed25519.keyId(publicKey)
        val timestamp = clockSeconds().toString()
        val nonce = newNonce()
        val canonical = RequestSigner.canonicalString("GET", path, "", timestamp, nonce)
        val signature = Ed25519.sign(seed, canonical.toByteArray(Charsets.UTF_8))
        return transport.request(
            "GET",
            "$baseUrl$path",
            mapOf(
                RequestSigner.HEADER_KEY to keyId,
                RequestSigner.HEADER_TIMESTAMP to timestamp,
                RequestSigner.HEADER_NONCE to nonce,
                RequestSigner.HEADER_SIGNATURE to Base64.getEncoder().encodeToString(signature)
            ),
            null,
            timeoutMs
        )
    }

    private fun answer(response: HttpJsonResponse): PairAnswer {
        val document = JsonParser.parseObjectOrNull(response.body)
            ?: return PairAnswer(
                response.status,
                PairingPort.error("PAIR_BAD_RESPONSE", "the peer answered non-JSON")
            )
        return PairAnswer(response.status, document)
    }

    private fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val STATUS_PATH: String = "/v1/status"
        const val PAIR_IDENTITY: String = "/v1/pair/identity"
        const val PAIR_HELLO: String = "/v1/pair/hello"
        const val PAIR_POLL: String = "/v1/pair/poll"
    }
}
