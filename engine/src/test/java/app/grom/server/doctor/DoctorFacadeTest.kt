// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge of the three Doctor sources (D80).
 *
 * The important cases are the ones the screen gets wrong if the merge is wrong:
 * measuring while the server is stopped (no status at all), and reading the
 * bridge's `configured`/`connected` pair without turning configuration into a
 * green row.
 */
class DoctorFacadeTest {

    private val probe = DoctorFacts(
        batteryExempt = true,
        backgroundRestricted = false,
        sdkInt = 34,
        measuredAtMs = 10L
    )

    private fun statusDocument(
        state: String = "READY",
        crashes: Int = 0,
        enabled: Boolean = true,
        running: Boolean = true,
        bridgeConfigured: Boolean = true,
        tunnels: List<Map<String, Any?>> = listOf(mapOf("state" to "connected"))
    ): Map<String, Any?> = mapOf(
        "state" to state,
        "crashes" to crashes,
        "restarts" to 1,
        "super" to mapOf(
            "enabled" to enabled,
            "running" to running,
            "bridge" to mapOf(
                "configured" to bridgeConfigured,
                "connected" to tunnels.any { it["state"] == "connected" },
                "tunnels" to tunnels
            )
        )
    )

    @Test
    fun `with no status the device checks still measure`() {
        val report = DoctorFacade.measure(probe, status = null, marker = null)
        val checks = report.checks.associateBy { it.id }
        // Battery and background came from the probe, so they are real values.
        assertEquals(DoctorLevel.OK, checks.getValue(DoctorRules.CHECK_BATTERY).level)
        assertEquals(DoctorLevel.OK, checks.getValue(DoctorRules.CHECK_BACKGROUND).level)
        // The server fields were never measured.
        assertEquals(DoctorLevel.UNKNOWN, checks.getValue(DoctorRules.CHECK_PROCESS).level)
        assertEquals(DoctorLevel.UNKNOWN, checks.getValue(DoctorRules.CHECK_AGENT).level)
    }

    @Test
    fun `status fields reach the facts and the verdict`() {
        val report = DoctorFacade.measure(probe, statusDocument())
        val facts = report.facts
        assertEquals("READY", facts.serverState)
        assertEquals(0, facts.crashes)
        assertEquals(1, facts.restarts)
        assertEquals(true, facts.dataPlaneRunning)
        assertEquals(true, facts.bridgeConnected)
        assertEquals(1, facts.tunnelsConnected)
    }

    @Test
    fun `a bridge with only stale tunnels is not connected`() {
        val report = DoctorFacade.measure(
            probe,
            statusDocument(tunnels = listOf(mapOf("state" to "closed"), mapOf("state" to "failed")))
        )
        assertEquals(false, report.facts.bridgeConnected)
        assertEquals(0, report.facts.tunnelsConnected)
        assertEquals(
            DoctorLevel.BAD,
            report.checks.first { it.id == DoctorRules.CHECK_BRIDGE }.level
        )
    }

    @Test
    fun `an enabled but stopped data plane is a fault, not a green row`() {
        val report = DoctorFacade.measure(
            probe,
            statusDocument(enabled = true, running = false)
        )
        val check = report.checks.first { it.id == DoctorRules.CHECK_DATA_PLANE }
        assertEquals(DoctorLevel.BAD, check.level)
        assertEquals("DATA_PLANE_NOT_RUNNING", check.code)
    }

    @Test
    fun `the marker supplies the one remembered fact`() {
        val marker = RunMarker(
            sessionStartedAtMs = 5_000L,
            cleanStop = false,
            unexpectedStops = 2,
            lastUnexpectedStopMs = 9_000L,
            previousSessionStartedAtMs = 5_000L
        )
        val report = DoctorFacade.measure(probe, statusDocument(), marker)
        assertEquals(2, report.facts.unexpectedStops)
        assertEquals(9_000L, report.facts.lastUnexpectedStopMs)
        assertEquals(5_000L, report.facts.previousSessionStartedAtMs)
    }

    @Test
    fun `the probe wins when there is no status or marker`() {
        val facts = DoctorFacade.facts(
            probe.copy(serverState = "STARTING", crashes = 4, lastUnexpectedStopMs = 1L)
        )
        assertEquals("STARTING", facts.serverState)
        assertEquals(4, facts.crashes)
        assertEquals(1L, facts.lastUnexpectedStopMs)
    }

    @Test
    fun `a missing status block does not erase a measured probe value`() {
        val facts = DoctorFacade.facts(
            probe.copy(dataPlaneRunning = true, bridgeConnected = true),
            status = mapOf("state" to "READY")
        )
        assertEquals(true, facts.dataPlaneRunning)
        assertEquals(true, facts.bridgeConnected)
        assertNull(facts.dataPlaneEnabled)
        assertNull(facts.bridgeConfigured)
    }

    @Test
    fun `measuring twice gives the same answer`() {
        val first = DoctorFacade.measure(probe, statusDocument())
        val second = DoctorFacade.measure(probe, statusDocument())
        assertEquals(first.verdict, second.verdict)
        assertEquals(first.checks, second.checks)
    }

    @Test
    fun `the measured time survives the merge`() {
        val facts = DoctorFacade.facts(probe, statusDocument())
        assertEquals(10L, facts.measuredAtMs)
        assertEquals(34, facts.sdkInt)
    }

    @Test
    fun `a status with a foreign numeric type still reads`() {
        // The JSON parser hands back Long for integers; the merge must not care.
        val status = mapOf<String, Any?>(
            "state" to "READY",
            "crashes" to 3L,
            "super" to mapOf(
                "enabled" to true,
                "running" to true,
                "bridge" to mapOf("configured" to false, "connected" to false, "tunnels" to emptyList<Any>())
            )
        )
        val facts = DoctorFacade.facts(probe, status)
        assertEquals(3, facts.crashes)
        assertTrue(facts.tunnelsConnected == 0)
    }
}
