// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

/**
 * The dashboard projection of a status document (S6, D56).
 *
 * This file is deliberately pure Kotlin: no `android.*` import, no resources,
 * no formatting. It decides *what* the operator must see and which lamp colour
 * each item gets; the activity decides how to say it. That split is what makes
 * the "no false green" rule testable on the JVM (D30).
 *
 * The rule: a lamp is ON only when the status contains a measured positive
 * value. `enabled = true` is configuration, never liveness, so it can never
 * turn a lamp green on its own.
 */
enum class Lamp { ON, OFF, UNKNOWN }

data class ServingRow(
    /** Stable id the activity maps to a localized title. */
    val id: String,
    /** Already-formatted value (an address, a label, a count); may be null. */
    val value: String?,
    val lamp: Lamp
)

data class Dashboard(
    /** `READY`, `MISSING_RUNTIME`, … or `OFFLINE` when no status exists. */
    val state: String,
    val stateLamp: Lamp,
    /** Address line under the headline; may be empty. */
    val detail: String,
    val rows: List<ServingRow>
)

object DashboardModel {

    const val STATE_OFFLINE: String = "OFFLINE"

    fun build(status: Map<String, Any?>?): Dashboard {
        if (status == null || status.isEmpty()) {
            return Dashboard(
                state = STATE_OFFLINE,
                stateLamp = Lamp.UNKNOWN,
                detail = "",
                rows = listOf(
                    ServingRow("control", null, Lamp.UNKNOWN),
                    ServingRow("data", null, Lamp.UNKNOWN),
                    ServingRow("bridge", null, Lamp.UNKNOWN),
                    ServingRow("project", null, Lamp.UNKNOWN)
                )
            )
        }

        val state = (status["state"] as? String) ?: STATE_OFFLINE
        val port = (status["port"] as? Number)?.toLong() ?: 0L
        val agentPort = (status["agentPort"] as? Number)?.toLong() ?: 0L
        val superBlock = status["super"] as? Map<*, *>

        val controlLamp = when {
            state == "STOPPED" -> Lamp.OFF
            state == "QUARANTINED" -> Lamp.OFF
            else -> Lamp.ON
        }
        val controlValue = if (port > 0) "127.0.0.1:$port" else null

        val superRunning = superBlock?.get("running") == true
        val superEnabled = superBlock?.get("enabled") == true
        val superError = superBlock?.get("error") as? String
        val dataLamp = when {
            superError != null -> Lamp.OFF
            superRunning -> Lamp.ON
            superEnabled -> Lamp.OFF
            else -> Lamp.UNKNOWN
        }
        val dataValue = when {
            superError != null -> null
            superRunning -> {
                val address = superBlock?.get("address") as? String
                address?.takeIf { it.isNotBlank() && it != "not bound" }
            }
            else -> null
        }

        val bridge = superBlock?.get("bridge") as? Map<*, *>
        val bridgeConfigured = bridge?.get("configured") == true
        val bridgeConnected = bridge?.get("connected") == true
        val bridgeLabel = (superBlock?.get("remoteBridge") as? Map<*, *>)?.get("label") as? String
        val bridgeLamp = when {
            !bridgeConfigured -> Lamp.UNKNOWN
            bridgeConnected -> Lamp.ON
            else -> Lamp.OFF
        }
        val bridgeValue = when {
            !bridgeConfigured -> null
            bridgeConnected && !bridgeLabel.isNullOrBlank() -> bridgeLabel
            bridgeConnected -> "connected"
            else -> "not connected"
        }

        val projects = status["projects"] as? List<*>
        val alive = projects
            ?.mapNotNull { it as? Map<*, *> }
            ?.firstOrNull { it["state"] == "alive" }
        val anyProject = projects?.isNotEmpty() == true
        val projectLamp = when {
            !anyProject -> Lamp.UNKNOWN
            alive != null -> Lamp.ON
            else -> Lamp.OFF
        }
        val projectValue = when {
            alive != null -> (alive["title"] as? String) ?: (alive["id"] as? String)
            anyProject -> "stale"
            else -> null
        }

        val detail = when {
            state == "READY" && agentPort > 0 -> "127.0.0.1:$port · agent :$agentPort"
            state == "READY" -> "127.0.0.1:$port"
            state == "QUARANTINED" -> (status["quarantineReason"] as? String).orEmpty()
            state == "MISSING_RUNTIME" -> (status["missing"] as? String).orEmpty()
            else -> ""
        }

        return Dashboard(
            state = state,
            stateLamp = when (state) {
                "READY" -> Lamp.ON
                "MISSING_RUNTIME", "QUARANTINED", "STOPPED" -> Lamp.OFF
                else -> Lamp.UNKNOWN
            },
            detail = detail,
            rows = listOf(
                ServingRow("control", controlValue, controlLamp),
                ServingRow("data", dataValue, dataLamp),
                ServingRow("bridge", bridgeValue, bridgeLamp),
                ServingRow("project", projectValue, projectLamp)
            )
        )
    }
}
