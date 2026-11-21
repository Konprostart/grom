// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.lightning

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.net.HttpJsonClient
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import java.net.URLEncoder
import java.util.Locale

/**
 * An adapter for an operator-run LNbits wallet (D43).
 *
 * LNbits is a wallet service, not an exchange: it can report a balance, mint a
 * BOLT11 invoice and pay one, but it has no hold invoices. That is why
 * [capabilities] deliberately omits `orderbook` and `escrow`; those operations
 * throw [LightningUnsupported] instead of inventing a result.
 *
 * The API key lives only in this object's memory and in the `X-Api-Key` header.
 * It is never part of a URL, an exception message or a return value.
 */
class LnbitsBackend(
    baseUrl: String,
    private val apiKey: String,
    private val transport: JsonTransport = HttpJsonClient(),
    private val timeoutMs: Int = 8_000
) : LightningBackend {

    private val base: String

    init {
        require(baseUrl.startsWith("https://")) { "baseUrl must use https://" }
        require(baseUrl.length <= MAX_URL_CHARS) { "baseUrl is too long" }
        require(baseUrl.none { it.isWhitespace() }) { "baseUrl must not contain whitespace" }
        val authority = baseUrl.substring("https://".length).substringBefore('/')
        require(!authority.contains('@')) { "baseUrl must not contain userinfo" }
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        base = stripTrailingSlashes(baseUrl)
    }

    override val name: String = "lnbits"

    override fun capabilities(): Set<LightningCapability> = setOf(
        LightningCapability.BALANCE,
        LightningCapability.CREATE_INVOICE,
        LightningCapability.PAYMENT_STATUS,
        LightningCapability.PAY
    )

    override fun health(): LightningHealth {
        return try {
            val response = transport.request(
                "GET",
                "$base/api/v1/wallet",
                headers(),
                null,
                timeoutMs
            )
            if (response.status == HTTP_OK) {
                LightningHealth(true, null)
            } else {
                LightningHealth(false, "HTTP ${response.status}")
            }
        } catch (_: RuntimeException) {
            // Health is a probe: an unreachable backend is an answer, not a throw.
            LightningHealth(false, "unreachable")
        }
    }

    override fun balance(): LightningBalance {
        val root = requireOk(send("GET", "/api/v1/wallet", null))
        val sats = jsonLong(root["balance"])
            ?: throw LightningUnavailable("wallet balance is missing or not numeric")
        val msat = try {
            Math.multiplyExact(sats, MILLIS_PER_SAT)
        } catch (_: ArithmeticException) {
            throw LightningUnavailable("wallet balance is out of range")
        }
        return LightningBalance(msat)
    }

    override fun createInvoice(
        amountMsat: Long,
        memo: String?,
        expirySeconds: Int?
    ): CreatedInvoice {
        if (amountMsat <= 0 || amountMsat % MILLIS_PER_SAT != 0L) {
            throw LightningBadRequest("LNbits accepts whole satoshis")
        }
        val sats = amountMsat / MILLIS_PER_SAT

        val payload = LinkedHashMap<String, Any?>()
        payload["out"] = false
        payload["amount"] = sats
        payload["memo"] = memo
        if (expirySeconds != null) {
            if (expirySeconds !in MIN_EXPIRY_SECONDS..MAX_EXPIRY_SECONDS) {
                throw LightningBadRequest("expirySeconds must be between 60 and 86400")
            }
            payload["expiry"] = expirySeconds
        }

        val response = send("POST", "/api/v1/payments", Json.encode(payload))
        if (response.status != HTTP_OK && response.status != HTTP_CREATED) {
            throw LightningUnavailable("HTTP ${response.status}")
        }
        val root = parseBody(response.body)
        val paymentHash = (root["payment_hash"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw LightningUnavailable("payment_hash is missing")
        val bolt11 = (root["payment_request"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw LightningUnavailable("payment_request is missing")
        return CreatedInvoice(paymentHash, bolt11, null)
    }

    override fun paymentStatus(paymentHash: String): PaymentState {
        val path = "/api/v1/payments/${encodeSegment(paymentHash)}"
        val root = requireOk(send("GET", path, null))
        val paid = root["paid"] as? Boolean
            ?: throw LightningUnavailable("paid is missing or not a boolean")
        val preimage = (root["preimage"] as? String)?.takeIf { it.isNotEmpty() }
        val amountSats = (root["details"] as? Map<*, *>)?.get("amount")
        val amountMsat = if (amountSats == null) {
            null
        } else {
            val sats = jsonLong(amountSats)
                ?: throw LightningUnavailable("details.amount is not numeric")
            try {
                Math.multiplyExact(sats, MILLIS_PER_SAT)
            } catch (_: ArithmeticException) {
                throw LightningUnavailable("details.amount is out of range")
            }
        }
        return PaymentState(
            paymentHash = paymentHash,
            status = if (paid) "settled" else "pending",
            paid = paid,
            preimage = preimage,
            amountMsat = amountMsat
        )
    }

    override fun pay(bolt11: String, maxFeeMsat: Long?): PaymentState {
        if (maxFeeMsat != null) {
            // LNbits has no fee-ceiling parameter. Ignoring an operator safety
            // limit would be worse than refusing the request.
            throw LightningUnsupported("pay.fee_limit")
        }
        val normalized = bolt11.lowercase(Locale.ROOT)
        if (PAYMENT_PREFIXES.none { normalized.startsWith(it) }) {
            throw LightningBadRequest("bolt11 must start with lnbc, lntb, lnbcrt or lnsb")
        }

        val payload = mapOf(
            "out" to true,
            "bolt11" to bolt11
        )
        val response = send("POST", "/api/v1/payments", Json.encode(payload))
        if (response.status != HTTP_OK && response.status != HTTP_CREATED) {
            throw LightningUnavailable("HTTP ${response.status}")
        }
        val root = parseBody(response.body)
        val paymentHash = (root["payment_hash"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw LightningUnavailable("payment_hash is missing")
        // Submission is not settlement: the wallet accepted the request, but the
        // payment has not necessarily completed yet.
        return PaymentState(
            paymentHash = paymentHash,
            status = "pending",
            paid = false,
            preimage = null,
            amountMsat = null
        )
    }

    override fun orderbook(): OrderbookSnapshot =
        throw LightningUnsupported(LightningCapability.ORDERBOOK.wireName)

    override fun escrowHold(paymentHash: String, amountMsat: Long, memo: String?): EscrowHandle =
        throw LightningUnsupported(LightningCapability.ESCROW.wireName)

    override fun escrowRelease(escrowId: String): EscrowHandle =
        throw LightningUnsupported(LightningCapability.ESCROW.wireName)

    override fun escrowRefund(escrowId: String): EscrowHandle =
        throw LightningUnsupported(LightningCapability.ESCROW.wireName)

    // ------------------------------------------------------------- internals

    private fun headers(): Map<String, String> = mapOf(
        "X-Api-Key" to apiKey,
        "Content-Type" to "application/json"
    )

    private fun send(method: String, path: String, body: String?): HttpJsonResponse {
        return try {
            transport.request(method, base + path, headers(), body, timeoutMs)
        } catch (_: TransportException) {
            throw LightningUnavailable("backend unreachable")
        }
    }

    private fun requireOk(response: HttpJsonResponse): Map<String, Any?> {
        if (response.status != HTTP_OK) {
            throw LightningUnavailable("HTTP ${response.status}")
        }
        return parseBody(response.body)
    }

    private fun parseBody(body: String): Map<String, Any?> {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw LightningUnavailable("response too large")
        }
        return JsonParser.parseObjectOrNull(body)
            ?: throw LightningUnavailable("backend returned a non-object response")
    }

    private fun jsonLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Double -> doubleToLong(value)
        is Float -> doubleToLong(value.toDouble())
        is Number -> doubleToLong(value.toDouble())
        else -> null
    }

    private fun doubleToLong(value: Double): Long? {
        if (value.isNaN() || value.isInfinite() || value != Math.floor(value)) return null
        if (value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()) return null
        return value.toLong()
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun stripTrailingSlashes(url: String): String {
        var result = url
        while (result.length > "https://".length && result.endsWith('/')) {
            result = result.dropLast(1)
        }
        return result
    }

    private companion object {

        const val MAX_RESPONSE_BYTES: Int = 64 * 1024
        const val MAX_URL_CHARS: Int = 512

        const val MILLIS_PER_SAT: Long = 1_000L
        const val HTTP_OK: Int = 200
        const val HTTP_CREATED: Int = 201
        const val MIN_EXPIRY_SECONDS: Int = 60
        const val MAX_EXPIRY_SECONDS: Int = 86_400

        val PAYMENT_PREFIXES = listOf("lnbc", "lntb", "lnbcrt", "lnsb")
    }
}
