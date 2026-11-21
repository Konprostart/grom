// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestSignerTest {

    private val seed = ByteArray(32) { index -> (index + 1).toByte() }
    private val publicKey = Ed25519.derivePublicKey(seed)
    private val keyId = Ed25519.keyId(publicKey)

    private var nowSeconds = 1_700_000_000L

    private fun signer(
        maxSkewSeconds: Long = 300L,
        lookup: (String) -> ByteArray? = { id -> if (id == keyId) publicKey else null }
    ): RequestSigner = RequestSigner(
        keyLookup = lookup,
        clock = { nowSeconds },
        maxSkewSeconds = maxSkewSeconds,
        nonceCache = NonceCache(clock = { nowSeconds * 1_000L })
    )

    @Test
    fun `valid signature is accepted`() {
        assertEquals(RequestSigner.Result.Ok, resultFor(signer(), signedHeaders()))
    }

    @Test
    fun `canonical string is exact`() {
        val canonical = signer().canonical("post", "/v1/orders", "{}", "1700000000", "abcdefgh")
        val expected = "grom-ed25519-v1\nPOST\n/v1/orders\n1700000000\nabcdefgh\n" +
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        assertEquals(expected, canonical)
        // Six lines, with no trailing newline after the body digest.
        assertEquals(6, canonical.split("\n").size)
    }

    @Test
    fun `timestamps on the edge of the window are accepted`() {
        assertEquals(
            RequestSigner.Result.Ok,
            resultFor(signer(), signedHeaders(timestamp = (nowSeconds - 300L).toString()))
        )
        assertEquals(
            RequestSigner.Result.Ok,
            resultFor(signer(), signedHeaders(timestamp = (nowSeconds + 300L).toString()))
        )
    }

    @Test
    fun `stale or malformed timestamps are rejected`() {
        assertCode(
            "SIGNATURE_STALE",
            resultFor(signer(), signedHeaders(timestamp = (nowSeconds - 301L).toString()))
        )
        assertCode(
            "SIGNATURE_STALE",
            resultFor(signer(), signedHeaders(timestamp = (nowSeconds + 301L).toString()))
        )
        assertCode(
            "SIGNATURE_STALE",
            resultFor(signer(), signedHeaders(timestamp = "not-a-number"))
        )
    }

    @Test
    fun `every signed header is required`() {
        for (name in HEADER_NAMES) {
            val headers = signedHeaders()
            headers.remove(name)
            assertCode("SIGNATURE_MISSING", resultFor(signer(), headers))
        }

        val blank = signedHeaders()
        blank["x-grom-key"] = "  "
        assertCode("SIGNATURE_MISSING", resultFor(signer(), blank))
    }

    @Test
    fun `unknown key id is rejected`() {
        assertCode(
            "SIGNATURE_UNKNOWN_KEY",
            resultFor(signer(), signedHeaders(key = "unknown-key-id"))
        )
        // A lookup that returns the wrong key size is treated as unknown.
        val shortKey = signer(lookup = { ByteArray(31) })
        assertCode("SIGNATURE_UNKNOWN_KEY", resultFor(shortKey, signedHeaders()))
    }

    @Test
    fun `tampered body is rejected`() {
        val headers = signedHeaders()
        assertCode("SIGNATURE_INVALID", resultFor(signer(), headers, body = BODY + " "))
        assertCode("SIGNATURE_INVALID", resultFor(signer(), headers, path = PATH + "/extra"))
        assertCode("SIGNATURE_INVALID", resultFor(signer(), headers, method = "PUT"))
    }

    @Test
    fun `malformed signature is rejected`() {
        val notBase64 = signedHeaders()
        notBase64["x-grom-signature"] = "!!!not-base64!!!"
        assertCode("SIGNATURE_INVALID", resultFor(signer(), notBase64))

        val wrongLength = signedHeaders()
        wrongLength["x-grom-signature"] = Base64.getEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(32))
        assertCode("SIGNATURE_INVALID", resultFor(signer(), wrongLength))
    }

    @Test
    fun `a signature that does not verify is rejected`() {
        val headers = signedHeaders()
        headers["x-grom-signature"] = Base64.getEncoder()
            .withoutPadding()
            .encodeToString(ByteArray(64))
        val result = resultFor(signer(), headers)
        assertCode("SIGNATURE_INVALID", result)
        assertEquals("signature does not verify", messageOf(result))
    }

    @Test
    fun `nonce is stored only after the signature verifies`() {
        val signer = signer()
        val headers = signedHeaders()

        assertCode("SIGNATURE_INVALID", resultFor(signer, headers, body = BODY + "x"))
        // The failed attempt must not have consumed the nonce.
        assertEquals(RequestSigner.Result.Ok, resultFor(signer, headers))
    }

    @Test
    fun `a replayed nonce is rejected`() {
        val signer = signer()
        val headers = signedHeaders()

        assertEquals(RequestSigner.Result.Ok, resultFor(signer, headers))
        val replayed = resultFor(signer, headers)
        assertCode("SIGNATURE_REPLAYED", replayed)
        assertEquals("nonce was already used", messageOf(replayed))
    }

    @Test
    fun `malformed nonces are rejected`() {
        for (nonce in listOf("short", "bad nonce here", "n".repeat(129), "nonce!")) {
            assertCode(
                "SIGNATURE_BAD_NONCE",
                resultFor(signer(), signedHeaders(nonce = nonce))
            )
        }
    }

    @Test
    fun `nonce length boundaries are accepted`() {
        assertEquals(
            RequestSigner.Result.Ok,
            resultFor(signer(), signedHeaders(nonce = "12345678"))
        )
        assertEquals(
            RequestSigner.Result.Ok,
            resultFor(signer(), signedHeaders(nonce = "a".repeat(128)))
        )
    }

    @Test
    fun `nonce cache remembers a nonce once inside the window`() {
        var millis = 1_000_000L
        val cache = NonceCache(maxEntries = 8, windowMs = 1_000L, clock = { millis })

        assertTrue(cache.remember("nonce-1"))
        assertFalse(cache.remember("nonce-1"))
        assertEquals(1, cache.size())

        millis += 1_001L
        assertTrue(cache.remember("nonce-1"))
    }

    @Test
    fun `nonce cache is bounded and evicts the oldest entry`() {
        var millis = 1_000_000L
        val cache = NonceCache(maxEntries = 2, windowMs = 60_000L, clock = { millis })

        assertTrue(cache.remember("nonce-1"))
        assertTrue(cache.remember("nonce-2"))
        assertTrue(cache.remember("nonce-3"))
        assertEquals(2, cache.size())

        // The oldest entry was evicted, so it is accepted again.
        assertTrue(cache.remember("nonce-1"))
        assertEquals(2, cache.size())
    }

    private fun resultFor(
        signer: RequestSigner,
        headers: Map<String, String>,
        method: String = "POST",
        path: String = PATH,
        body: String = BODY
    ): RequestSigner.Result = signer.verify(method, path, body) { name -> headers[name] }

    private fun signedHeaders(
        method: String = "POST",
        path: String = PATH,
        body: String = BODY,
        timestamp: String = nowSeconds.toString(),
        nonce: String = "nonce-0123456789",
        key: String = keyId
    ): MutableMap<String, String> {
        val canonical = signer().canonical(method, path, body, timestamp, nonce)
        val signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Ed25519.sign(seed, canonical.toByteArray(Charsets.UTF_8)))
        return mutableMapOf(
            "x-grom-key" to key,
            "x-grom-timestamp" to timestamp,
            "x-grom-nonce" to nonce,
            "x-grom-signature" to signature
        )
    }

    private fun assertCode(expected: String, result: RequestSigner.Result) {
        assertEquals(expected, codeOf(result))
    }

    private fun codeOf(result: RequestSigner.Result): String {
        assertTrue("expected a rejection but was $result", result is RequestSigner.Result.Rejected)
        return (result as RequestSigner.Result.Rejected).code
    }

    private fun messageOf(result: RequestSigner.Result): String {
        assertTrue("expected a rejection but was $result", result is RequestSigner.Result.Rejected)
        return (result as RequestSigner.Result.Rejected).message
    }

    private companion object {
        const val PATH = "/v1/orders"
        const val BODY = """{"amountMsat":1000}"""
        val HEADER_NAMES = listOf(
            "x-grom-key",
            "x-grom-timestamp",
            "x-grom-nonce",
            "x-grom-signature"
        )
    }
}
