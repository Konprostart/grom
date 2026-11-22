// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

/**
 * The fleet projection the data plane may report (D59, D73).
 *
 * It names this device and nothing else: no paired-device list, no address, no
 * key, no scope. A caller reaching `/v1/status` learns that it is talking to
 * node `ab12cd34ef567890`; the roster stays on the device, returned only over
 * the signature-gated Binder. Keeping this a separate object makes the absence
 * of those fields a property a gate can check rather than a habit.
 */
object FleetStatus {

    fun document(identity: NodeIdentity?): Map<String, Any?> {
        if (identity == null) return emptyMap()
        return linkedMapOf(
            "nodeId" to identity.nodeId,
            "label" to identity.label
        )
    }
}
