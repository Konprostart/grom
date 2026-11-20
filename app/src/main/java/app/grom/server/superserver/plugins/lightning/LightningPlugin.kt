// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.lightning

import app.grom.server.superserver.Plugin
import app.grom.server.superserver.PluginResult

/**
 * The public `lightning` plugin (D39, D43).
 *
 * This class is the honesty boundary: it validates arguments, negotiates the
 * capability against what the configured adapter actually implements, and maps
 * backend failures to stable API errors. It never substitutes a value for a
 * measurement it does not have.
 */
class LightningPlugin(
    private val backend: LightningBackend?,
    private val configuredKind: String = "none",
    private val configurationError: String? = null
) : Plugin {

    override val id: String = "lightning"

    override val title: String = "Lightning"

    override val description: String =
        "Operator-configured Lightning backend adapter; Grom never custodies funds."

    override fun capabilities(): Set<String> =
        backend?.capabilities()?.map { it.wireName }?.toSet() ?: emptySet()

    override fun status(): Map<String, Any?> = mapOf(
        "configured" to (backend != null),
        "backend" to (backend?.name ?: configuredKind),
        "capabilities" to capabilities().sorted(),
        "error" to configurationError
    )

    override fun rpc(op: String, args: Map<String, Any?>): PluginResult {
        val active = backend
            ?: return PluginResult.Failure(
                503,
                "LIGHTNING_UNCONFIGURED",
                "no Lightning backend is configured"
            )

        return try {
            val required = requiredCapability(op)
            if (required != null && required !in active.capabilities()) {
                return PluginResult.Failure(
                    501,
                    "NOT_SUPPORTED",
                    "backend does not support ${required.wireName}"
                )
            }

            when (op) {
                "health" -> {
                    val health = active.health()
                    PluginResult.Ok(
                        mapOf(
                            "backend" to active.name,
                            "reachable" to health.reachable,
                            "detail" to health.detail
                        )
                    )
                }

                "status" -> PluginResult.Ok(status())

                "balance" -> {
                    val balance = active.balance()
                    PluginResult.Ok(mapOf("msat" to balance.msat))
                }

                "invoice.create" -> {
                    val amountMsat = amountArgument(
                        args["amountMsat"],
                        "amountMsat",
                        MAX_INVOICE_MSAT
                    )
                    val memo = optionalMemo(args["memo"], MAX_MEMO_CHARS)
                    val expiry = optionalInt(args["expirySeconds"], "expirySeconds")
                    if (expiry != null && expiry !in MIN_EXPIRY_SECONDS..MAX_EXPIRY_SECONDS) {
                        throw InvalidArguments(
                            "expirySeconds must be between 60 and 86400"
                        )
                    }
                    val invoice = active.createInvoice(amountMsat, memo, expiry)
                    PluginResult.Ok(
                        mapOf(
                            "paymentHash" to invoice.paymentHash,
                            "bolt11" to invoice.bolt11,
                            "expiresAtMs" to invoice.expiresAtMs
                        )
                    )
                }

                "invoice.status" -> {
                    val paymentHash = paymentHashArgument(args["paymentHash"])
                    PluginResult.Ok(paymentStateMap(active.paymentStatus(paymentHash)))
                }

                "pay" -> {
                    val bolt11 = bolt11Argument(args["bolt11"])
                    val maxFeeMsat = optionalPositiveLong(args["maxFeeMsat"], "maxFeeMsat")
                    PluginResult.Ok(paymentStateMap(active.pay(bolt11, maxFeeMsat)))
                }

                "orderbook" -> {
                    val orderbook = active.orderbook()
                    PluginResult.Ok(
                        mapOf(
                            "source" to orderbook.source,
                            "bids" to orderbook.bids.map { orderbookLevelMap(it) },
                            "asks" to orderbook.asks.map { orderbookLevelMap(it) }
                        )
                    )
                }

                "escrow.hold" -> {
                    val paymentHash = paymentHashArgument(args["paymentHash"])
                    val amountMsat = amountArgument(args["amountMsat"], "amountMsat")
                    val memo = optionalString(args["memo"], "memo")
                    PluginResult.Ok(
                        escrowMap(active.escrowHold(paymentHash, amountMsat, memo))
                    )
                }

                "escrow.release", "escrow.refund" -> {
                    val escrowId = escrowIdArgument(args["escrowId"])
                    val handle = if (op == "escrow.release") {
                        active.escrowRelease(escrowId)
                    } else {
                        active.escrowRefund(escrowId)
                    }
                    PluginResult.Ok(escrowMap(handle))
                }

                else -> PluginResult.Failure(400, "UNKNOWN_OP", "unknown op")
            }
        } catch (failure: InvalidArguments) {
            PluginResult.Failure(400, "INVALID_ARGS", failure.message ?: "invalid arguments")
        } catch (failure: LightningUnsupported) {
            PluginResult.Failure(501, "NOT_SUPPORTED", failure.message ?: "not supported")
        } catch (failure: LightningUnavailable) {
            PluginResult.Failure(
                503,
                "LIGHTNING_UNAVAILABLE",
                failure.message ?: "backend unavailable"
            )
        } catch (failure: LightningBadRequest) {
            PluginResult.Failure(400, "INVALID_ARGS", failure.message ?: "invalid arguments")
        } catch (failure: LightningBackendException) {
            PluginResult.Failure(
                502,
                "LIGHTNING_BACKEND_ERROR",
                failure.message ?: "backend error"
            )
        } catch (_: ArithmeticException) {
            PluginResult.Failure(400, "INVALID_ARGS", "amount out of range")
        } catch (_: RuntimeException) {
            // The message is deliberately constant: an unexpected exception may
            // carry a backend URL or credential and must not reach the client.
            PluginResult.Failure(500, "INTERNAL_ERROR", "lightning adapter failed")
        }
    }

    // ------------------------------------------------------------- arguments

    private class InvalidArguments(message: String) : RuntimeException(message)

    private fun requiredCapability(op: String): LightningCapability? = when (op) {
        "balance" -> LightningCapability.BALANCE
        "invoice.create" -> LightningCapability.CREATE_INVOICE
        "invoice.status" -> LightningCapability.PAYMENT_STATUS
        "pay" -> LightningCapability.PAY
        "orderbook" -> LightningCapability.ORDERBOOK
        "escrow.hold", "escrow.release", "escrow.refund" -> LightningCapability.ESCROW
        else -> null
    }

    /**
     * Accepts both JSON numbers and decimal strings, rejects non-finite values
     * and negatives, and refuses fractions. Money is never a floating point.
     */
    private fun longArgument(value: Any?, name: String): Long {
        if (value == null) throw InvalidArguments("$name is required")
        val number: Number = when (value) {
            is Number -> value
            is String -> numericString(value, name)
            else -> throw InvalidArguments("$name must be a number")
        }
        val asDouble = number.toDouble()
        if (asDouble.isNaN() || asDouble.isInfinite()) {
            throw InvalidArguments("$name must be a finite number")
        }
        if (asDouble < 0) throw InvalidArguments("$name must not be negative")
        val result = when (number) {
            is Long -> number
            is Int -> number.toLong()
            is Short -> number.toLong()
            is Byte -> number.toLong()
            is Double -> integral(number, name)
            is Float -> integral(number.toDouble(), name)
            else -> integral(asDouble, name)
        }
        if (result < 0L) throw InvalidArguments("$name must not be negative")
        return result
    }

    private fun numericString(raw: String, name: String): Number {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw InvalidArguments("$name must be a number")
        trimmed.toLongOrNull()?.let { return it }
        return trimmed.toDoubleOrNull() ?: throw InvalidArguments("$name must be a number")
    }

    private fun integral(value: Double, name: String): Long {
        if (value.isNaN() || value.isInfinite() || value != Math.floor(value)) {
            throw InvalidArguments("$name must be an integer")
        }
        if (value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()) {
            throw InvalidArguments("$name is out of range")
        }
        return value.toLong()
    }

    private fun amountArgument(value: Any?, name: String, max: Long? = null): Long {
        val amount = longArgument(value, name)
        if (amount <= 0L) throw InvalidArguments("$name must be greater than zero")
        if (max != null && amount > max) throw InvalidArguments("$name is too large")
        return amount
    }

    private fun optionalPositiveLong(value: Any?, name: String): Long? {
        if (value == null) return null
        val number = longArgument(value, name)
        if (number <= 0L) throw InvalidArguments("$name must be greater than zero")
        return number
    }

    private fun optionalInt(value: Any?, name: String): Int? {
        if (value == null) return null
        val number = longArgument(value, name)
        if (number > Int.MAX_VALUE) throw InvalidArguments("$name is out of range")
        return number.toInt()
    }

    private fun optionalMemo(value: Any?, maxLength: Int): String? = when (value) {
        null -> null
        is String -> {
            if (value.length > maxLength) throw InvalidArguments("memo is too long")
            value
        }
        else -> throw InvalidArguments("memo must be a string")
    }

    private fun optionalString(value: Any?, name: String): String? = when (value) {
        null -> null
        is String -> value
        else -> throw InvalidArguments("$name must be a string")
    }

    private fun paymentHashArgument(value: Any?): String {
        val text = value as? String
            ?: throw InvalidArguments("paymentHash must be a string")
        if (!HEX_64.matches(text)) {
            throw InvalidArguments("paymentHash must be 64 hexadecimal characters")
        }
        return text
    }

    private fun escrowIdArgument(value: Any?): String {
        val text = value as? String
            ?: throw InvalidArguments("escrowId must be a string")
        if (text.length !in 1..128) {
            throw InvalidArguments("escrowId must be 1..128 characters")
        }
        return text
    }

    private fun bolt11Argument(value: Any?): String {
        val text = value as? String
            ?: throw InvalidArguments("bolt11 must be a string")
        if (text.length !in 20..20_000) {
            throw InvalidArguments("bolt11 must be 20..20000 characters")
        }
        return text
    }

    private fun paymentStateMap(state: PaymentState): Map<String, Any?> = mapOf(
        "paymentHash" to state.paymentHash,
        "status" to state.status,
        "paid" to state.paid,
        "preimage" to state.preimage,
        "amountMsat" to state.amountMsat
    )

    private fun orderbookLevelMap(level: OrderbookLevel): Map<String, Any?> = mapOf(
        "priceMsatPerUnit" to level.priceMsatPerUnit,
        "amountMsat" to level.amountMsat
    )

    private fun escrowMap(handle: EscrowHandle): Map<String, Any?> = mapOf(
        "id" to handle.id,
        "state" to handle.state,
        "paymentHash" to handle.paymentHash
    )

    private companion object {

        const val MAX_INVOICE_MSAT: Long = 1_000_000_000_000L
        const val MAX_MEMO_CHARS: Int = 128
        const val MIN_EXPIRY_SECONDS: Int = 60
        const val MAX_EXPIRY_SECONDS: Int = 86_400

        val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    }
}
