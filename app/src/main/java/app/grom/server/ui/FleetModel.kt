// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R

/**
 * The measured state of one paired device (D74).
 *
 * Pure Kotlin, no `android.*`, no network: the screen only maps data the engine
 * already measured. A lamp turns green for `alive` and for nothing else — a
 * stored pairing, a configured address and a label are never evidence that the
 * other phone is reachable. `unknown` (never checked) and `stale` (checked a
 * while ago) are grey on purpose.
 */
object FleetModel {

    fun lampOf(peer: Map<*, *>?): Lamp {
        if (peer == null) return Lamp.UNKNOWN
        return when (peer["state"] as? String) {
            "alive" -> Lamp.ON
            "unreachable" -> Lamp.OFF
            else -> Lamp.UNKNOWN
        }
    }

    fun stateRes(state: String?): Int = when (state) {
        "alive" -> R.string.fleet_state_alive
        "stale" -> R.string.fleet_state_stale
        "unreachable" -> R.string.fleet_state_unreachable
        else -> R.string.fleet_state_unknown
    }

    /** `1234-5678-9abc-def0`; anything else is returned unchanged. */
    fun fingerprint(nodeId: String?): String {
        val id = (nodeId ?: "").trim()
        if (!Regex("^[0-9a-f]{16}$").matches(id)) return id
        return buildString {
            for (index in id.indices) {
                if (index > 0 && index % 4 == 0) append('-')
                append(id[index])
            }
        }
    }

    /**
     * The section header count: `"alive/paired"`, e.g. `"1/2"`.
     *
     * Only a measured `alive` counts as connected; a stored pairing that has
     * never answered stays in the denominator and never turns green.
     */
    fun summary(peers: List<Map<String, Any?>>): String {
        val alive = peers.count { it["state"] == "alive" }
        return "$alive/${peers.size}"
    }

    fun scopesText(peer: Map<*, *>?): String {
        val scopes = (peer?.get("grantedByMe") as? List<*>)
            ?.mapNotNull { it as? String }
            ?: emptyList()
        return if (scopes.isEmpty()) "" else scopes.joinToString(", ")
    }

    /**
     * A one-way grant (D77) has no address, so this device never dials it. The
     * row says so instead of showing an empty "Address:" line, and the lamp
     * stays grey: nothing was measured.
     */
    fun isInboundOnly(peer: Map<*, *>?): Boolean =
        (peer?.get("address") as? String).isNullOrEmpty()
}
