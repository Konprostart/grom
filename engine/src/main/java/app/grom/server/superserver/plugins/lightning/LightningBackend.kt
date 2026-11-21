// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.lightning

/**
 * The only thing a Lightning integration may be in Grom: an adapter (D43).
 *
 * Grom does not hold keys, does not sign, does not custody and does not match
 * orders. A backend is an external service the *operator* configured; Grom
 * forwards a request and relays the answer. Every method either returns a value
 * that the backend produced or throws — there is no default, no zero balance and
 * no optimistic `pending`.
 *
 * Capability negotiation is explicit: a backend that has no hold-invoice support
 * does not silently pretend; it omits [LightningCapability.ESCROW] and the plugin
 * answers `501 NOT_SUPPORTED`.
 */
enum class LightningCapability(val wireName: String) {
    BALANCE("balance"),
    CREATE_INVOICE("invoice.create"),
    PAYMENT_STATUS("invoice.status"),
    PAY("pay"),
    ORDERBOOK("orderbook"),
    ESCROW("escrow")
}

open class LightningBackendException(message: String) : RuntimeException(message)

/** The configured backend could not be reached or answered garbage. */
class LightningUnavailable(message: String) : LightningBackendException(message)

/** The backend is reachable but does not implement this capability. */
class LightningUnsupported(val capability: String) :
    LightningBackendException("backend does not support $capability")

/** The caller supplied something the backend (or this adapter) rejects. */
class LightningBadRequest(message: String) : LightningBackendException(message)

data class LightningHealth(
    val reachable: Boolean,
    val detail: String?
)

/** Amounts are always in millisatoshis; there is no floating point money. */
data class LightningBalance(val msat: Long)

data class CreatedInvoice(
    val paymentHash: String,
    val bolt11: String,
    val expiresAtMs: Long?
)

data class PaymentState(
    val paymentHash: String,
    val status: String,
    val paid: Boolean,
    val preimage: String?,
    val amountMsat: Long?
)

data class OrderbookLevel(
    val priceMsatPerUnit: Long,
    val amountMsat: Long
)

data class OrderbookSnapshot(
    val source: String,
    val bids: List<OrderbookLevel>,
    val asks: List<OrderbookLevel>
)

/** A hold/escrow handle. The backend owns the funds; Grom only tracks the id. */
data class EscrowHandle(
    val id: String,
    val state: String,
    val paymentHash: String?
)

interface LightningBackend {

    /** Stable adapter name, e.g. `lnbits`. */
    val name: String

    fun capabilities(): Set<LightningCapability>

    fun health(): LightningHealth

    fun balance(): LightningBalance

    fun createInvoice(amountMsat: Long, memo: String?, expirySeconds: Int?): CreatedInvoice

    fun paymentStatus(paymentHash: String): PaymentState

    fun pay(bolt11: String, maxFeeMsat: Long?): PaymentState

    fun orderbook(): OrderbookSnapshot

    fun escrowHold(paymentHash: String, amountMsat: Long, memo: String?): EscrowHandle

    fun escrowRelease(escrowId: String): EscrowHandle

    fun escrowRefund(escrowId: String): EscrowHandle
}
