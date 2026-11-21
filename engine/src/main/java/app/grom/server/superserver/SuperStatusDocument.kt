// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.superserver.nostr.Nip19
import app.grom.server.superserver.nostr.RelayUrl

/**
 * The reference block of the status document (D52, D53).
 *
 * It is attached to the authenticated `/v1/status` and to the `agent` plugin's
 * `status` op, never to the unauthenticated `/v1/health` (D39). It contains no
 * secret: an `npub` is a public key, and the relay list is just URLs.
 *
 * `lightningHealth.reachable` is `null` until the adapter has actually been
 * probed. Grom does not turn "configured" into "healthy".
 */
object SuperStatusDocument {

    const val CONFIG_VERSION: Int = 1

    fun build(
        config: SuperServerConfig,
        lightning: Map<String, Any?>
    ): Map<String, Any?> = linkedMapOf(
        "npub" to config.npub?.let { Nip19.normalizeNpub(it) },
        "relays" to config.relays.mapNotNull { RelayUrl.normalizeRelay(it) },
        "remoteBridge" to config.remoteBridge
            ?.takeUnless { it.isEmpty() }
            ?.let {
                linkedMapOf(
                    "label" to it.label.trim(),
                    "description" to it.description.trim()
                )
            },
        "lightningHealth" to linkedMapOf(
            "configured" to (lightning["configured"] == true),
            "backend" to (lightning["backend"] as? String),
            // null means "not probed yet", not "unhealthy".
            "reachable" to (lightning["reachable"] as? Boolean),
            "error" to ((lightning["error"] ?: lightning["detail"]) as? String)
        ),
        // Kind and label only. The endpoint is the operator's own address and
        // stays on the device (D63).
        "externalNode" to config.lightning.externalNode
            .takeUnless { it.isEmpty() }
            ?.toPublicJson()
    )
}
