// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

import app.grom.server.http.Json

/**
 * The serialised Doctor report (D80, D81).
 *
 * Built from an explicit allow-list, never by reflecting over [DoctorFacts], so a
 * field added later cannot leak by accident. That matters more here than
 * anywhere else: "Copy diagnostics" copies exactly this document so an operator
 * can paste it into a bug report, and a report is only safe to paste if it cannot
 * contain a token, a chat id, an SSH host, a key or a paired device's address.
 *
 * Deliberately absent: the agent token and its fingerprint, the Telegram chat id,
 * SSH hosts and users, peer addresses, file paths. `tool/test-policy` asserts
 * those identifiers do not appear in this file.
 */
object DoctorDocument {

    /** The three fields that may leave the device in `/v1/status` (D84). */
    fun statusBlock(facts: DoctorFacts): Map<String, Any?> = linkedMapOf(
        "batteryExempt" to facts.batteryExempt,
        "backgroundRestricted" to facts.backgroundRestricted,
        "lastUnexpectedStopMs" to (facts.lastUnexpectedStopMs ?: 0L)
    )

    /** The full document behind `doctorJson()` on the Binder (API 8). */
    fun document(report: DoctorReport): Map<String, Any?> = linkedMapOf(
        "verdict" to report.verdict.name,
        "measuredAtMs" to report.facts.measuredAtMs,
        "counts" to linkedMapOf(
            "ok" to report.count(DoctorLevel.OK),
            "bad" to report.count(DoctorLevel.BAD),
            "notice" to report.count(DoctorLevel.NOTICE),
            "unknown" to report.count(DoctorLevel.UNKNOWN)
        ),
        "checks" to report.checks.map { check ->
            linkedMapOf(
                "id" to check.id,
                "level" to check.level.name,
                "code" to check.code
            )
        },
        "facts" to facts(report.facts)
    )

    /** `Json.encode(document(...))`; one call site so the shape cannot drift. */
    fun json(report: DoctorReport): String = Json.encode(document(report))

    private fun facts(facts: DoctorFacts): Map<String, Any?> = linkedMapOf(
        "sdkInt" to facts.sdkInt,
        "batteryExempt" to facts.batteryExempt,
        "backgroundRestricted" to facts.backgroundRestricted,
        "standbyBucket" to facts.standbyBucket,
        "powerSaver" to facts.powerSaver,
        "notificationsEnabled" to facts.notificationsEnabled,
        "deviceIdle" to facts.deviceIdle,
        "thermalStatus" to facts.thermalStatus,
        "batteryPercent" to facts.batteryPercent,
        "charging" to facts.charging,
        "lowRamDevice" to facts.lowRamDevice,
        "freeBytes" to facts.freeBytes,
        "availableMemBytes" to facts.availableMemBytes,
        "lowMemory" to facts.lowMemory,
        "network" to facts.network,
        "metered" to facts.metered,
        "serverState" to facts.serverState,
        "crashes" to facts.crashes,
        "restarts" to facts.restarts,
        "dataPlaneEnabled" to facts.dataPlaneEnabled,
        "dataPlaneRunning" to facts.dataPlaneRunning,
        "bridgeConfigured" to facts.bridgeConfigured,
        "bridgeConnected" to facts.bridgeConnected,
        "tunnelsConnected" to facts.tunnelsConnected,
        "lastUnexpectedStopMs" to facts.lastUnexpectedStopMs,
        "unexpectedStops" to facts.unexpectedStops,
        "previousSessionStartedAtMs" to facts.previousSessionStartedAtMs,
        "lastExitReason" to facts.lastExitReason,
        "lastExitAtMs" to facts.lastExitAtMs
    )

}
