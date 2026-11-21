// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R

/**
 * The Telegram-alerts row of the dashboard (D65-D67).
 *
 * Like [DashboardModel] this file is pure Kotlin — no `android.*`, no resources
 * beyond the ids it returns — so "no false green" is testable on the JVM.
 *
 * The rule, the same one every lamp follows (D56): green only for a measured
 * positive. `enabled = true` is configuration. `configured = true` is
 * configuration. Neither can turn the lamp green; only a delivery the provider
 * accepted can, and a rejection turns it red.
 */
enum class AlertState { OFF, NOT_CONFIGURED, READY, LAST_OK, LAST_FAILED }

data class AlertsRow(
    val state: AlertState,
    val lamp: Lamp,
    /** Localized label for the row's value. */
    val valueRes: Int,
    /** Localized short detail under the row; 0 when there is nothing to add. */
    val detailRes: Int = 0
)

object NotifyModel {

    /**
     * @param block the `notifications` map from the status document, or null
     *   when the status predates the feature / the server is not running.
     */
    fun row(block: Map<*, *>?): AlertsRow {
        if (block == null) {
            return AlertsRow(
                state = AlertState.OFF,
                lamp = Lamp.UNKNOWN,
                valueRes = R.string.alerts_state_off
            )
        }
        val enabled = block["enabled"] == true
        val configured = block["configured"] == true
        val last = block["lastDelivery"] as? Map<*, *>
        val lastOk = last?.get("ok") as? Boolean

        if (!enabled) {
            return AlertsRow(
                state = AlertState.OFF,
                lamp = Lamp.OFF,
                valueRes = R.string.alerts_state_off
            )
        }
        if (!configured) {
            return AlertsRow(
                state = AlertState.NOT_CONFIGURED,
                lamp = Lamp.OFF,
                valueRes = R.string.alerts_state_not_configured,
                detailRes = R.string.alerts_not_configured_hint
            )
        }
        return when (lastOk) {
            true -> AlertsRow(
                state = AlertState.LAST_OK,
                lamp = Lamp.ON,
                valueRes = R.string.alerts_state_delivered
            )
            false -> AlertsRow(
                state = AlertState.LAST_FAILED,
                lamp = Lamp.OFF,
                valueRes = R.string.alerts_state_failed
            )
            // Enabled and configured, but nothing has been sent: grey, because
            // nothing has been measured yet.
            null -> AlertsRow(
                state = AlertState.READY,
                lamp = Lamp.UNKNOWN,
                valueRes = R.string.alerts_state_ready,
                detailRes = R.string.alerts_ready_hint
            )
        }
    }
}
