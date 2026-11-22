// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions of the Doctor screen (D80, D83).
 *
 * This is the file that keeps the screen honest, so it asserts the rules rather
 * than a few examples of them:
 *
 *  - a green row is only ever produced by a value the platform returned;
 *  - configuration (`enabled`, `configured`, a saved tunnel) can never be green;
 *  - a missing fact is `UNKNOWN`, and the verdict is never `PASS` while a row is
 *    unknown — a green headline must not hide an unmeasured line.
 */
class DoctorRulesTest {

    /** A report where every check measured fine, so a change stands out. */
    private fun healthy() = DoctorFacts(
        batteryExempt = true,
        backgroundRestricted = false,
        standbyBucket = DoctorRules.BUCKET_ACTIVE,
        powerSaver = false,
        notificationsEnabled = true,
        deviceIdle = false,
        thermalStatus = 0,
        batteryPercent = 80,
        charging = true,
        lowRamDevice = false,
        freeBytes = 8L * 1024L * 1024L * 1024L,
        availableMemBytes = 2L * 1024L * 1024L * 1024L,
        lowMemory = false,
        network = "wifi",
        metered = false,
        serverState = "READY",
        crashes = 0,
        restarts = 0,
        dataPlaneEnabled = true,
        dataPlaneRunning = true,
        bridgeConfigured = true,
        bridgeConnected = true,
        tunnelsConnected = 1,
        // A clean, recorded exit, so the fixture is genuinely all-OK and the
        // verdict test really tests the verdict rather than a missing fact.
        lastExitReason = "USER_STOPPED",
        lastExitAtMs = 1_699_999_999_000L,
        sdkInt = 34,
        measuredAtMs = 1_700_000_000_000L
    )

    private fun check(report: DoctorReport, id: String): DoctorCheck =
        report.checks.first { it.id == id }

    @Test
    fun `a fully measured healthy device passes`() {
        val report = DoctorRules.evaluate(healthy())
        assertEquals(DoctorVerdict.PASS, report.verdict)
        assertEquals(0, report.count(DoctorLevel.BAD))
        assertEquals(0, report.count(DoctorLevel.UNKNOWN))
        assertTrue(report.checks.all { it.id in DoctorRules.ORDER })
    }

    @Test
    fun `the checks come out in the documented order`() {
        val report = DoctorRules.evaluate(healthy())
        assertEquals(DoctorRules.ORDER, report.checks.map { it.id })
    }

    @Test
    fun `nothing measured is not a pass`() {
        val report = DoctorRules.evaluate(DoctorFacts(sdkInt = 34))
        assertEquals(DoctorVerdict.NOTHING_MEASURED, report.verdict)
        assertTrue(report.checks.all { it.level == DoctorLevel.UNKNOWN })
    }

    @Test
    fun `an unknown row keeps the verdict from turning green`() {
        // Everything measured fine except standby, which Android did not answer.
        val report = DoctorRules.evaluate(healthy().copy(standbyBucket = null))
        assertEquals(DoctorVerdict.PARTLY_UNKNOWN, report.verdict)
        assertEquals(DoctorLevel.UNKNOWN, check(report, DoctorRules.CHECK_STANDBY).level)
        assertNotEquals(DoctorVerdict.PASS, report.verdict)
    }

    @Test
    fun `battery exemption is measured, never assumed`() {
        assertEquals(
            DoctorLevel.OK,
            check(DoctorRules.evaluate(healthy()), DoctorRules.CHECK_BATTERY).level
        )
        assertEquals(
            DoctorLevel.BAD,
            check(
                DoctorRules.evaluate(healthy().copy(batteryExempt = false)),
                DoctorRules.CHECK_BATTERY
            ).level
        )
        // Null is unknown, not "optimised" and not "fine".
        assertEquals(
            DoctorLevel.UNKNOWN,
            check(
                DoctorRules.evaluate(healthy().copy(batteryExempt = null)),
                DoctorRules.CHECK_BATTERY
            ).level
        )
    }

    @Test
    fun `background restriction and standby buckets map at the documented edges`() {
        val restricted = DoctorRules.evaluate(healthy().copy(backgroundRestricted = true))
        assertEquals(DoctorLevel.BAD, check(restricted, DoctorRules.CHECK_BACKGROUND).level)

        val okBuckets = listOf(
            DoctorRules.BUCKET_EXEMPTED,
            DoctorRules.BUCKET_ACTIVE,
            DoctorRules.BUCKET_WORKING_SET
        )
        for (bucket in okBuckets) {
            assertEquals(
                "bucket $bucket should be ok",
                DoctorLevel.OK,
                check(
                    DoctorRules.evaluate(healthy().copy(standbyBucket = bucket)),
                    DoctorRules.CHECK_STANDBY
                ).level
            )
        }
        assertEquals(
            DoctorLevel.NOTICE,
            check(
                DoctorRules.evaluate(healthy().copy(standbyBucket = DoctorRules.BUCKET_FREQUENT)),
                DoctorRules.CHECK_STANDBY
            ).level
        )
        val badBuckets = listOf(
            DoctorRules.BUCKET_RARE,
            DoctorRules.BUCKET_RESTRICTED,
            DoctorRules.BUCKET_NEVER
        )
        for (bucket in badBuckets) {
            assertEquals(
                "bucket $bucket should be bad",
                DoctorLevel.BAD,
                check(
                    DoctorRules.evaluate(healthy().copy(standbyBucket = bucket)),
                    DoctorRules.CHECK_STANDBY
                ).level
            )
        }
    }

    @Test
    fun `a hidden foreground service is a fault`() {
        val report = DoctorRules.evaluate(healthy().copy(notificationsEnabled = false))
        assertEquals(DoctorLevel.BAD, check(report, DoctorRules.CHECK_NOTIFICATIONS).level)
    }

    @Test
    fun `agent stability warns at one crash and faults at three`() {
        assertEquals(
            DoctorLevel.OK,
            check(DoctorRules.evaluate(healthy().copy(crashes = 0)), DoctorRules.CHECK_AGENT).level
        )
        assertEquals(
            DoctorLevel.NOTICE,
            check(DoctorRules.evaluate(healthy().copy(crashes = 1)), DoctorRules.CHECK_AGENT).level
        )
        assertEquals(
            DoctorLevel.NOTICE,
            check(DoctorRules.evaluate(healthy().copy(crashes = 2)), DoctorRules.CHECK_AGENT).level
        )
        assertEquals(
            DoctorLevel.BAD,
            check(DoctorRules.evaluate(healthy().copy(crashes = 3)), DoctorRules.CHECK_AGENT).level
        )
    }

    @Test
    fun `the data plane is green only when measured running, not when enabled`() {
        // Enabled but not running: configuration cannot be green (D56).
        val enabledOnly = DoctorRules.evaluate(
            healthy().copy(dataPlaneEnabled = true, dataPlaneRunning = false)
        )
        assertEquals(
            DoctorLevel.BAD,
            check(enabledOnly, DoctorRules.CHECK_DATA_PLANE).level
        )
        // Running is enough even if the config read did not arrive.
        val runningOnly = DoctorRules.evaluate(
            healthy().copy(dataPlaneEnabled = null, dataPlaneRunning = true)
        )
        assertEquals(
            DoctorLevel.OK,
            check(runningOnly, DoctorRules.CHECK_DATA_PLANE).level
        )
        // Deliberately off is a notice, not a fault: most phones do not need it.
        val off = DoctorRules.evaluate(
            healthy().copy(dataPlaneEnabled = false, dataPlaneRunning = false)
        )
        assertEquals(DoctorLevel.NOTICE, check(off, DoctorRules.CHECK_DATA_PLANE).level)
        // Nothing measured at all.
        val unknown = DoctorRules.evaluate(
            healthy().copy(dataPlaneEnabled = null, dataPlaneRunning = null)
        )
        assertEquals(
            DoctorLevel.UNKNOWN,
            check(unknown, DoctorRules.CHECK_DATA_PLANE).level
        )
    }

    @Test
    fun `a saved tunnel is not a connected tunnel`() {
        val saved = DoctorRules.evaluate(
            healthy().copy(bridgeConfigured = true, bridgeConnected = false)
        )
        assertEquals(DoctorLevel.BAD, check(saved, DoctorRules.CHECK_BRIDGE).level)

        val none = DoctorRules.evaluate(
            healthy().copy(bridgeConfigured = false, bridgeConnected = false)
        )
        assertEquals(DoctorLevel.NOTICE, check(none, DoctorRules.CHECK_BRIDGE).level)

        val connected = DoctorRules.evaluate(
            healthy().copy(bridgeConfigured = null, bridgeConnected = true)
        )
        assertEquals(DoctorLevel.OK, check(connected, DoctorRules.CHECK_BRIDGE).level)
    }

    @Test
    fun `server state maps to its own verdict`() {
        fun levelFor(state: String?) = check(
            DoctorRules.evaluate(healthy().copy(serverState = state)),
            DoctorRules.CHECK_PROCESS
        ).level

        assertEquals(DoctorLevel.OK, levelFor("READY"))
        assertEquals(DoctorLevel.NOTICE, levelFor("STARTING"))
        assertEquals(DoctorLevel.BAD, levelFor("STOPPED"))
        assertEquals(DoctorLevel.BAD, levelFor("QUARANTINED"))
        assertEquals(DoctorLevel.BAD, levelFor("MISSING_RUNTIME"))
        assertEquals(DoctorLevel.UNKNOWN, levelFor(null))
        assertEquals(DoctorLevel.UNKNOWN, levelFor("SOMETHING_NEW"))
    }

    @Test
    fun `with no status an unclean end is still a measurement`() {
        val report = DoctorRules.evaluate(
            DoctorFacts(sdkInt = 34, serverState = null, unexpectedStops = 2, lastUnexpectedStopMs = 5L)
        )
        val process = check(report, DoctorRules.CHECK_PROCESS)
        assertEquals(DoctorLevel.BAD, process.level)
        assertEquals("PROCESS_STOPPED_UNCLEAN", process.code)
    }

    @Test
    fun `every exit reason maps to the documented level`() {
        fun exit(reason: String?) = check(
            DoctorRules.evaluate(healthy().copy(lastExitReason = reason)),
            DoctorRules.CHECK_LAST_EXIT
        )

        for (clean in DoctorRules.CLEAN_EXIT_REASONS) {
            assertEquals("$clean is a clean end", DoctorLevel.OK, exit(clean).level)
        }
        for (forced in DoctorRules.FORCED_EXIT_REASONS) {
            assertEquals("$forced is a forced end", DoctorLevel.BAD, exit(forced).level)
        }
        // Reasons we recognise by name but do not classify are a notice, not a fault.
        assertEquals(DoctorLevel.NOTICE, exit("OTHER").level)
        assertEquals(DoctorLevel.NOTICE, exit("UNKNOWN").level)
        assertEquals("EXIT_OTHER", exit("OTHER").code)
    }

    @Test
    fun `an unrecorded exit is bad when the marker saw it`() {
        val report = DoctorRules.evaluate(
            healthy().copy(
                lastExitReason = null,
                lastUnexpectedStopMs = 1_700_000_000_000L,
                unexpectedStops = 1
            )
        )
        val exit = check(report, DoctorRules.CHECK_LAST_EXIT)
        assertEquals(DoctorLevel.BAD, exit.level)
        assertEquals("EXIT_UNRECORDED", exit.code)
    }

    @Test
    fun `before api 30 the exit row says so instead of guessing`() {
        val report = DoctorRules.evaluate(
            healthy().copy(lastExitReason = null, lastUnexpectedStopMs = null, sdkInt = 29)
        )
        val exit = check(report, DoctorRules.CHECK_LAST_EXIT)
        assertEquals(DoctorLevel.UNKNOWN, exit.level)
        assertEquals("EXIT_NOT_AVAILABLE", exit.code)

        // API 30+ with nothing recorded is a different sentence.
        val newer = DoctorRules.evaluate(
            healthy().copy(lastExitReason = null, lastUnexpectedStopMs = null, sdkInt = 30)
        )
        assertEquals(
            "EXIT_NONE_RECORDED",
            check(newer, DoctorRules.CHECK_LAST_EXIT).code
        )
    }

    @Test
    fun `free space is judged against the documented threshold`() {
        val justEnough = DoctorRules.evaluate(
            healthy().copy(freeBytes = DoctorRules.MIN_FREE_BYTES)
        )
        assertEquals(DoctorLevel.OK, check(justEnough, DoctorRules.CHECK_STORAGE).level)

        val tooLittle = DoctorRules.evaluate(
            healthy().copy(freeBytes = DoctorRules.MIN_FREE_BYTES - 1)
        )
        val storage = check(tooLittle, DoctorRules.CHECK_STORAGE)
        assertEquals(DoctorLevel.BAD, storage.level)
        assertEquals("STORAGE_LOW", storage.code)

        val unknown = DoctorRules.evaluate(healthy().copy(freeBytes = null))
        assertEquals(DoctorLevel.UNKNOWN, check(unknown, DoctorRules.CHECK_STORAGE).level)
    }

    @Test
    fun `a bad row outranks an unknown one in the verdict`() {
        val report = DoctorRules.evaluate(
            healthy().copy(batteryExempt = false, standbyBucket = null)
        )
        assertEquals(DoctorVerdict.ATTENTION, report.verdict)
    }

    @Test
    fun `thermals and battery level never turn a lamp green or red`() {
        // They are context, not checks: a hot phone or a flat battery is shown in
        // "Right now" and must not be dressed up as a Doctor fault.
        val hot = DoctorRules.evaluate(
            healthy().copy(thermalStatus = 4, batteryPercent = 3, charging = false)
        )
        assertEquals(DoctorVerdict.PASS, hot.verdict)
        assertNull(hot.checks.firstOrNull { it.id == "thermal" })
        assertNull(hot.checks.firstOrNull { it.id == "battery_level" })
    }
}
