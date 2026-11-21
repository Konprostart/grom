// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.http.Json
import app.grom.server.superserver.nostr.Nip19
import app.grom.server.superserver.nostr.RelayUrl

/**
 * The "Copy config" export (D53).
 *
 * This is a hand-off document for an external VPS bridge. It is built from an
 * explicit allow-list, never by serialising [SuperServerConfig], so a credential
 * cannot leak through a field added later. The allow-list is: public key, relay
 * URLs, bridge label, the data-plane shape and the Lightning backend's location
 * and measured health.
 *
 * Deliberately absent: the password hash, any Ed25519 public key material, the
 * sealed backend credential, session tokens and audit records. The gate
 * `tools/test-policy` asserts that the secret identifiers do not appear here.
 */
object ConfigExport {

    private const val SOURCE: String = "grom"

    fun render(
        config: SuperServerConfig,
        lightning: Map<String, Any?>,
        projects: List<Map<String, Any?>> = emptyList(),
        bridge: Map<String, Any?> = emptyMap()
    ): String = Json.encode(
        linkedMapOf(
            "source" to SOURCE,
            "configVersion" to SuperStatusDocument.CONFIG_VERSION,
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
            // Measured, not configured: `connected` is true only while a tunnel
            // is actually up. The host, the user and the pin stay on the phone.
            "bridge" to linkedMapOf(
                "configured" to (bridge["configured"] == true),
                "connected" to (bridge["connected"] == true),
                "active" to ((bridge["active"] as? Number)?.toInt() ?: 0)
            ),
            "projects" to projects.map { project ->
                linkedMapOf(
                    "id" to (project["id"] as? String),
                    "title" to (project["title"] as? String),
                    "version" to (project["version"] as? String),
                    "capabilities" to (project["capabilities"] as? List<*>),
                    "state" to (project["state"] as? String)
                )
            },
            "dataPlane" to linkedMapOf(
                "enabled" to config.enabled,
                "bind" to config.bind.name,
                "port" to config.port,
                "auth" to config.auth.name,
                "statusPath" to "/v1/status"
            ),
            "lightning" to linkedMapOf(
                "kind" to config.lightning.kind.wireName,
                "baseUrl" to config.lightning.baseUrl.trim(),
                "health" to linkedMapOf(
                    "configured" to (lightning["configured"] == true),
                    "reachable" to (lightning["reachable"] as? Boolean)
                )
            )
        )
    )
}
