// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.lightning

import app.grom.server.superserver.PluginResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightningPluginTest {

    private class FakeBackend(
        private val caps: Set<LightningCapability> = setOf(
            LightningCapability.BALANCE,
            LightningCapability.CREATE_INVOICE,
            LightningCapability.PAYMENT_STATUS,
            LightningCapability.PAY,
            LightningCapability.ORDERBOOK,
            LightningCapability.ESCROW
        ),
        override val name: String = "fake"
    ) : LightningBackend {

        var balanceCalls: Int = 0
        var createCalls: Int = 0
        var failure: RuntimeException? = null

        var balanceValue: LightningBalance = LightningBalance(42_000L)
        var invoice: CreatedInvoice = CreatedInvoice("ab".repeat(32), "lnbc1invoice", null)
        var payment: PaymentState =
            PaymentState("ab".repeat(32), "settled", true, "preimage", 42_000L)
        var orderbookValue: OrderbookSnapshot = OrderbookSnapshot(
            "fake",
            listOf(OrderbookLevel(1_000L, 2_000L)),
            listOf(OrderbookLevel(1_100L, 3_000L))
        )
        var escrow: EscrowHandle = EscrowHandle("escrow-1", "held", "ab".repeat(32))

        var lastCreateAmount: Long = -1L
        var lastMemo: String? = null
        var lastExpiry: Int? = null
        var lastBolt11: String? = null
        var lastMaxFeeMsat: Long? = null

        private fun maybeFail() {
            failure?.let { throw it }
        }

        override fun capabilities(): Set<LightningCapability> = caps

        override fun health(): LightningHealth {
            maybeFail()
            return LightningHealth(true, null)
        }

        override fun balance(): LightningBalance {
            balanceCalls += 1
            maybeFail()
            return balanceValue
        }

        override fun createInvoice(
            amountMsat: Long,
            memo: String?,
            expirySeconds: Int?
        ): CreatedInvoice {
            createCalls += 1
            maybeFail()
            lastCreateAmount = amountMsat
            lastMemo = memo
            lastExpiry = expirySeconds
            return invoice
        }

        override fun paymentStatus(paymentHash: String): PaymentState {
            maybeFail()
            return payment.copy(paymentHash = paymentHash)
        }

        override fun pay(bolt11: String, maxFeeMsat: Long?): PaymentState {
            maybeFail()
            lastBolt11 = bolt11
            lastMaxFeeMsat = maxFeeMsat
            return payment
        }

        override fun orderbook(): OrderbookSnapshot {
            maybeFail()
            return orderbookValue
        }

        override fun escrowHold(
            paymentHash: String,
            amountMsat: Long,
            memo: String?
        ): EscrowHandle {
            maybeFail()
            return escrow.copy(paymentHash = paymentHash)
        }

        override fun escrowRelease(escrowId: String): EscrowHandle {
            maybeFail()
            return escrow.copy(id = escrowId)
        }

        override fun escrowRefund(escrowId: String): EscrowHandle {
            maybeFail()
            return escrow.copy(id = escrowId)
        }
    }

    private val hash: String = "ab".repeat(32)

    @Test
    fun `an unconfigured plugin answers 503`() {
        val failure = LightningPlugin(null).rpc("balance", emptyMap()) as PluginResult.Failure

        assertEquals(503, failure.status)
        assertEquals("LIGHTNING_UNCONFIGURED", failure.code)
        assertEquals("no Lightning backend is configured", failure.message)
    }

    @Test
    fun `a missing capability is refused before the backend is called`() {
        val backend = FakeBackend(caps = setOf(LightningCapability.CREATE_INVOICE))

        val failure = LightningPlugin(backend).rpc("balance", emptyMap()) as PluginResult.Failure

        assertEquals(501, failure.status)
        assertEquals("NOT_SUPPORTED", failure.code)
        assertEquals("backend does not support balance", failure.message)
        assertEquals(0, backend.balanceCalls)
    }

    @Test
    fun `balance passes the measured value through`() {
        val backend = FakeBackend()
        backend.balanceValue = LightningBalance(123_000L)

        val ok = LightningPlugin(backend).rpc("balance", emptyMap()) as PluginResult.Ok

        assertEquals(mapOf("msat" to 123_000L), ok.payload)
    }

    @Test
    fun `invalid amount is INVALID_ARGS`() {
        val backend = FakeBackend()
        val plugin = LightningPlugin(backend)

        for (amount in listOf(-1L, 0L, 1_000_000_000_001L)) {
            val failure = plugin.rpc(
                "invoice.create",
                mapOf("amountMsat" to amount)
            ) as PluginResult.Failure
            assertEquals(400, failure.status)
            assertEquals("INVALID_ARGS", failure.code)
        }
        assertEquals(0, backend.createCalls)
    }

    @Test
    fun `invoice create accepts a numeric string and relays the result`() {
        val backend = FakeBackend()

        val ok = LightningPlugin(backend).rpc(
            "invoice.create",
            mapOf("amountMsat" to "5000", "memo" to "note", "expirySeconds" to 600)
        ) as PluginResult.Ok

        assertEquals(5_000L, backend.lastCreateAmount)
        assertEquals("note", backend.lastMemo)
        assertEquals(600, backend.lastExpiry)
        assertEquals(hash, ok.payload["paymentHash"])
        assertEquals("lnbc1invoice", ok.payload["bolt11"])
        assertNull(ok.payload["expiresAtMs"])
    }

    @Test
    fun `invoice status requires a 64 character hex payment hash`() {
        val backend = FakeBackend()
        val plugin = LightningPlugin(backend)

        val failure = plugin.rpc(
            "invoice.status",
            mapOf("paymentHash" to "not-a-hash")
        ) as PluginResult.Failure
        assertEquals(400, failure.status)
        assertEquals("INVALID_ARGS", failure.code)

        val ok = plugin.rpc(
            "invoice.status",
            mapOf("paymentHash" to hash)
        ) as PluginResult.Ok
        assertEquals(hash, ok.payload["paymentHash"])
        assertEquals("settled", ok.payload["status"])
        assertTrue(ok.payload["paid"] as Boolean)
    }

    @Test
    fun `unknown op is UNKNOWN_OP`() {
        val failure = LightningPlugin(FakeBackend()).rpc(
            "wallet.drain",
            emptyMap()
        ) as PluginResult.Failure

        assertEquals(400, failure.status)
        assertEquals("UNKNOWN_OP", failure.code)
    }

    @Test
    fun `backend exceptions map to stable API errors`() {
        val cases = listOf(
            LightningUnsupported("escrow") to (501 to "NOT_SUPPORTED"),
            LightningUnavailable("HTTP 401") to (503 to "LIGHTNING_UNAVAILABLE"),
            LightningBadRequest("bad amount") to (400 to "INVALID_ARGS"),
            LightningBackendException("backend exploded") to (502 to "LIGHTNING_BACKEND_ERROR")
        )

        for ((cause, expected) in cases) {
            val backend = FakeBackend()
            backend.failure = cause

            val failure = LightningPlugin(backend).rpc(
                "balance",
                emptyMap()
            ) as PluginResult.Failure

            assertEquals(expected.first, failure.status)
            assertEquals(expected.second, failure.code)
        }
    }

    @Test
    fun `unexpected runtime exceptions do not leak their message`() {
        val backend = FakeBackend()
        backend.failure = RuntimeException("https://lnbits.example?key=secret-token")

        val failure = LightningPlugin(backend).rpc(
            "balance",
            emptyMap()
        ) as PluginResult.Failure

        assertEquals(500, failure.status)
        assertEquals("INTERNAL_ERROR", failure.code)
        assertEquals("lightning adapter failed", failure.message)
        assertFalse(failure.message.contains("secret-token"))
    }

    @Test
    fun `orderbook keeps the declared shape`() {
        val ok = LightningPlugin(FakeBackend()).rpc(
            "orderbook",
            emptyMap()
        ) as PluginResult.Ok

        assertEquals("fake", ok.payload["source"])

        val bids = ok.payload["bids"] as List<*>
        assertEquals(1, bids.size)
        val bid = bids[0] as Map<*, *>
        assertEquals(1_000L, bid["priceMsatPerUnit"])
        assertEquals(2_000L, bid["amountMsat"])

        val asks = ok.payload["asks"] as List<*>
        assertEquals(1, asks.size)
        val ask = asks[0] as Map<*, *>
        assertEquals(1_100L, ask["priceMsatPerUnit"])
        assertEquals(3_000L, ask["amountMsat"])
    }

    @Test
    fun `escrow operations return the backend handle`() {
        val plugin = LightningPlugin(FakeBackend())

        val held = plugin.rpc(
            "escrow.hold",
            mapOf("paymentHash" to hash, "amountMsat" to 5_000L, "memo" to "hold it")
        ) as PluginResult.Ok
        assertEquals("escrow-1", held.payload["id"])
        assertEquals("held", held.payload["state"])
        assertEquals(hash, held.payload["paymentHash"])

        val released = plugin.rpc(
            "escrow.release",
            mapOf("escrowId" to "escrow-1")
        ) as PluginResult.Ok
        assertEquals("escrow-1", released.payload["id"])

        val refunded = plugin.rpc(
            "escrow.refund",
            mapOf("escrowId" to "escrow-1")
        ) as PluginResult.Ok
        assertEquals("escrow-1", refunded.payload["id"])
    }

    @Test
    fun `status exposes no secret fields`() {
        val status = LightningPlugin(FakeBackend(), "lnbits", null).status()

        assertEquals(
            setOf(
                "configured",
                "backend",
                "capabilities",
                "error",
                "reachable",
                "probedAtMs",
                "detail"
            ),
            status.keys
        )
        assertEquals(true, status["configured"])
        assertEquals("fake", status["backend"])
        assertEquals(
            listOf(
                "balance",
                "invoice.create",
                "invoice.status",
                "orderbook",
                "pay",
                "escrow"
            ).sorted(),
            status["capabilities"]
        )
        assertNull(status["error"])
        assertNull(status["reachable"])
        assertNull(status["probedAtMs"])
        assertNull(status["detail"])
        assertFalse(status.containsKey("apiKey"))
        assertFalse(status.containsKey("token"))
        assertFalse(status.containsKey("secret"))
    }

    @Test
    fun `status reports a measured health result and nothing before it`() {
        val backend = FakeBackend()
        var now = 1_000L
        val plugin = LightningPlugin(backend, "fake", null) { now }

        // Before any probe, reachable is unknown, not false and not true.
        assertNull(plugin.status()["reachable"])
        assertNull(plugin.status()["probedAtMs"])

        val ok = plugin.rpc("health", emptyMap()) as PluginResult.Ok
        assertEquals(true, ok.payload["reachable"])

        // The timestamp is the time of the probe, not the time of the status read.
        val status = plugin.status()
        assertEquals(true, status["reachable"])
        assertEquals(1_000L, status["probedAtMs"])

        now = 5_000L
        plugin.rpc("health", emptyMap())
        assertEquals(5_000L, plugin.status()["probedAtMs"])
    }

    @Test
    fun `status never calls the backend`() {
        val backend = FakeBackend()
        backend.failure = RuntimeException("backend must not be touched")
        val plugin = LightningPlugin(backend, "fake", null)

        // If status probed the backend, this would throw.
        assertEquals(true, plugin.status()["configured"])
        assertNull(plugin.status()["reachable"])
    }
}
