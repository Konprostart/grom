// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

/**
 * Assembles one measurement out of the three sources Doctor is allowed to use
 * (D80), and nothing else:
 *
 *  - the **probe**: what this Android says right now ([DoctorFacts] from
 *    `AndroidDoctorProbe`, all of it nullable);
 *  - the **engine status**: what the server already measured about itself —
 *    the same document the dashboard reads, so the two screens cannot disagree;
 *  - the **run marker**: the one remembered fact, whether the previous process
 *    ended without `onDestroy` (D83).
 *
 * Pure Kotlin on purpose. The Android probe is the only file that talks to the
 * system, so every merge below — including the "a saved tunnel is not a
 * connected tunnel" distinction — is testable on the JVM (D30).
 *
 * With no status (the server is stopped, or the caller is the activity before
 * the Binder binds), the device checks still run: "why did it die" is exactly
 * the question that has no Binder to ask.
 */
object DoctorFacade {

    /**
     * @param probe what the platform returned; nulls where it returned nothing.
     * @param status the engine status document, or null when there is none.
     * @param marker the run marker as last read, or null when unaffected.
     */
    fun facts(
        probe: DoctorFacts,
        status: Map<String, Any?>? = null,
        marker: RunMarker? = null
    ): DoctorFacts {
        val superBlock = status?.get("super") as? Map<*, *>
        val bridgeBlock = superBlock?.get("bridge") as? Map<*, *>
        val tunnels = (bridgeBlock?.get("tunnels") as? List<*>)
            ?.count { (it as? Map<*, *>)?.get("state") == "connected" }

        return probe.copy(
            serverState = (status?.get("state") as? String) ?: probe.serverState,
            crashes = number(status?.get("crashes")) ?: probe.crashes,
            restarts = number(status?.get("restarts")) ?: probe.restarts,
            // `enabled` is configuration; `running` is the measurement. Both are
            // carried so the rule can refuse to colour the row from the first.
            dataPlaneEnabled = (superBlock?.get("enabled") as? Boolean)
                ?: probe.dataPlaneEnabled,
            dataPlaneRunning = (superBlock?.get("running") as? Boolean)
                ?: probe.dataPlaneRunning,
            bridgeConfigured = (bridgeBlock?.get("configured") as? Boolean)
                ?: probe.bridgeConfigured,
            bridgeConnected = (bridgeBlock?.get("connected") as? Boolean)
                ?: probe.bridgeConnected,
            tunnelsConnected = tunnels ?: probe.tunnelsConnected,
            lastUnexpectedStopMs = marker?.lastUnexpectedStopMs ?: probe.lastUnexpectedStopMs,
            unexpectedStops = marker?.unexpectedStops ?: probe.unexpectedStops,
            previousSessionStartedAtMs = marker?.previousSessionStartedAtMs
                ?: probe.previousSessionStartedAtMs
        )
    }

    /** The one entry point every caller uses: merge, then decide. */
    fun measure(
        probe: DoctorFacts,
        status: Map<String, Any?>? = null,
        marker: RunMarker? = null
    ): DoctorReport = DoctorRules.evaluate(facts(probe, status, marker))

    private fun number(value: Any?): Int? = (value as? Number)?.toInt()
}
