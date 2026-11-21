// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.lightning

import app.grom.server.http.JsonParser
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LnbitsBackendTest {

    private class FakeTransport(
        private val handler: (Request) -> HttpJsonResponse
    ) : JsonTransport {

        data class Request(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: String?,
            val timeoutMs: Int
        )

        val requests = ArrayList<Request>()

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int
        ): HttpJsonResponse {
            val request = Request(method, url, headers, body, timeoutMs)
            requests.add(request)
            return handler(request)
        }

        fun last(): Request = requests.last()
    }

    private val base: String = "https://lnbits.example"
    private val apiKey: String = "test-api-key"
    private val hash: String = "ab".repeat(32)

    private fun backend(
        handler: (FakeTransport.Request) -> HttpJsonResponse
    ): Pair<LnbitsBackend, FakeTransport> {
        val transport = FakeTransport(handler)
        return LnbitsBackend(base, apiKey, transport) to transport
    }

    private fun ok(body: String): HttpJsonResponse = HttpJsonResponse(200, body)

    @Test
    fun `balance converts sats to millisatoshis`() {
        val (adapter, transport) = backend { ok("""{"balance":1234}""") }

        assertEquals(1_234_000L, adapter.balance().msat)

        val request = transport.last()
        assertEquals("GET", request.method)
        assertEquals("$base/api/v1/wallet", request.url)
        assertEquals(apiKey, request.headers["X-Api-Key"])
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test
    fun `createInvoice posts whole satoshis and parses the invoice`() {
        val (adapter, transport) = backend {
            ok("""{"payment_hash":"$hash","payment_request":"lnbc1invoice"}""")
        }

        val invoice = adapter.createInvoice(150_000L, "coffee", 3_600)

        assertEquals(hash, invoice.paymentHash)
        assertEquals("lnbc1invoice", invoice.bolt11)
        assertNull(invoice.expiresAtMs)

        val request = transport.last()
        assertEquals("POST", request.method)
        assertEquals("$base/api/v1/payments", request.url)
        assertEquals(apiKey, request.headers["X-Api-Key"])
        assertEquals("application/json", request.headers["Content-Type"])

        val body = JsonParser.parseObjectOrNull(request.body!!)!!
        assertEquals(false, body["out"])
        assertEquals(150L, body["amount"])
        assertEquals("coffee", body["memo"])
        assertEquals(3_600L, body["expiry"])
    }

    @Test
    fun `createInvoice rejects an amount that is not whole satoshis`() {
        val (adapter, transport) = backend { ok("{}") }

        assertThrows(LightningBadRequest::class.java) {
            adapter.createInvoice(1_500L, null, null)
        }
        assertThrows(LightningBadRequest::class.java) {
            adapter.createInvoice(0L, null, null)
        }
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `paymentStatus maps paid and pending without inventing failed`() {
        val (paidAdapter, _) = backend {
            ok("""{"paid":true,"preimage":"deadbeef","details":{"amount":42}}""")
        }
        val settled = paidAdapter.paymentStatus(hash)
        assertEquals("settled", settled.status)
        assertTrue(settled.paid)
        assertEquals("deadbeef", settled.preimage)
        assertEquals(42_000L, settled.amountMsat)

        val (pendingAdapter, _) = backend { ok("""{"paid":false}""") }
        val pending = pendingAdapter.paymentStatus(hash)
        assertEquals("pending", pending.status)
        assertFalse(pending.paid)
        assertNull(pending.preimage)
        assertNull(pending.amountMsat)
    }

    @Test
    fun `pay refuses a fee ceiling and a malformed invoice`() {
        val (adapter, transport) = backend { ok("""{"payment_hash":"$hash"}""") }

        val unsupported = assertThrows(LightningUnsupported::class.java) {
            adapter.pay("lnbc1invoice", 1_000L)
        }
        assertEquals("pay.fee_limit", unsupported.capability)

        assertThrows(LightningBadRequest::class.java) {
            adapter.pay("not-a-lightning-invoice", null)
        }
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `pay returns pending after the wallet accepts the request`() {
        val (adapter, _) = backend { ok("""{"payment_hash":"$hash"}""") }

        val state = adapter.pay("lnbc1invoice", null)

        assertEquals(hash, state.paymentHash)
        assertEquals("pending", state.status)
        assertFalse(state.paid)
        assertNull(state.preimage)
        assertNull(state.amountMsat)
    }

    @Test
    fun `health is false on an HTTP error`() {
        val (adapter, _) = backend { HttpJsonResponse(401, """{"detail":"bad key"}""") }

        val health = adapter.health()

        assertFalse(health.reachable)
        assertEquals("HTTP 401", health.detail)
    }

    @Test
    fun `health is false when the transport fails`() {
        val (adapter, _) = backend { throw TransportException("transport failure") }

        val health = adapter.health()

        assertFalse(health.reachable)
        assertEquals("unreachable", health.detail)
    }

    @Test
    fun `missing or malformed fields are unavailable`() {
        assertThrows(LightningUnavailable::class.java) {
            backend { ok("""{"not_balance":1}""") }.first.balance()
        }
        assertThrows(LightningUnavailable::class.java) {
            backend { ok("""{"payment_hash":"$hash"}""") }.first.createInvoice(1_000L, null, null)
        }
        assertThrows(LightningUnavailable::class.java) {
            backend { ok("""{"preimage":"deadbeef"}""") }.first.paymentStatus(hash)
        }
        assertThrows(LightningUnavailable::class.java) {
            backend { ok("""{"ok":true}""") }.first.pay("lnbc1invoice", null)
        }
        assertThrows(LightningUnavailable::class.java) {
            backend { HttpJsonResponse(500, "{}") }.first.balance()
        }
    }

    @Test
    fun `orderbook and escrow are unsupported, never fabricated`() {
        val (adapter, transport) = backend { ok("{}") }

        assertThrows(LightningUnsupported::class.java) { adapter.orderbook() }
        assertThrows(LightningUnsupported::class.java) {
            adapter.escrowHold(hash, 1_000L, null)
        }
        assertThrows(LightningUnsupported::class.java) {
            adapter.escrowRelease("escrow-1")
        }
        assertThrows(LightningUnsupported::class.java) {
            adapter.escrowRefund("escrow-1")
        }
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `capabilities are the wallet operations, not exchange operations`() {
        val (adapter, _) = backend { ok("{}") }

        assertEquals(
            setOf(
                LightningCapability.BALANCE,
                LightningCapability.CREATE_INVOICE,
                LightningCapability.PAYMENT_STATUS,
                LightningCapability.PAY
            ),
            adapter.capabilities()
        )
    }
}
