// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import app.grom.server.doctor.DoctorCheck
import app.grom.server.doctor.DoctorFacts
import app.grom.server.doctor.DoctorLevel
import app.grom.server.doctor.DoctorReport
import app.grom.server.doctor.DoctorRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host-facing projection of a Doctor report (D90).
 *
 * The rule that matters most is the same one the Doctor screen has: nothing here
 * may be greener than the measurement it came from. These tests assert that
 * directly — a report with any `BAD` row is never `RUNNING`, a report with an
 * unm easured row is never `RUNNING`, and only a fully measured healthy report
 * produces `SERVER_RUNNING`.
 */
class EmbedHealthTest {

    private fun report(vararg checks: DoctorCheck): DoctorReport {
        val list = checks.toList()
        return DoctorReport(
            verdict = DoctorRules.verdictOf(list),
            checks = list,
            facts = DoctorFacts(measuredAtMs = 42L)
        )
    }

    private fun ok(id: String) = DoctorCheck(id, DoctorLevel.OK)
    private fun bad(id: String, code: String) = DoctorCheck(id, DoctorLevel.BAD, code)
    private fun unknown(id: String, code: String) = DoctorCheck(id, DoctorLevel.UNKNOWN, code)
    private fun notice(id: String, code: String) = DoctorCheck(id, DoctorLevel.NOTICE, code)

    private fun healthy(vararg extra: DoctorCheck): DoctorReport = report(
        ok(DoctorRules.CHECK_PROCESS),
        ok(DoctorRules.CHECK_LAST_EXIT),
        ok(DoctorRules.CHECK_BATTERY),
        ok(DoctorRules.CHECK_BACKGROUND),
        ok(DoctorRules.CHECK_STANDBY),
        ok(DoctorRules.CHECK_NOTIFICATIONS),
        ok(DoctorRules.CHECK_AGENT),
        ok(DoctorRules.CHECK_DATA_PLANE),
        ok(DoctorRules.CHECK_BRIDGE),
        ok(DoctorRules.CHECK_STORAGE),
        *extra
    )

    @Test
    fun `a fully measured healthy report is the only running state`() {
        val health = EmbedHealth.of(healthy())

        assertEquals(EmbedState.RUNNING, health.state)
        assertEquals(EmbedHealth.SERVER_RUNNING, health.headline)
        assertTrue(health.advice.isEmpty())
        assertEquals(10, health.counts.ok)
        assertEquals(10, health.counts.total)
        assertEquals(42L, health.measuredAtMs)
    }

    @Test
    fun `a bad measurement is never reported as running`() {
        val health = EmbedHealth.of(
            report(
                ok(DoctorRules.CHECK_PROCESS),
                bad(DoctorRules.CHECK_BATTERY, "BATTERY_OPTIMISED")
            )
        )

        assertEquals(EmbedState.ATTENTION, health.state)
        assertEquals(EmbedHealth.NEEDS_ATTENTION, health.headline)
        assertFalse(health.state == EmbedState.RUNNING)
    }

    @Test
    fun `an unmeasured row keeps the state unknown, not green`() {
        val health = EmbedHealth.of(
            report(
                ok(DoctorRules.CHECK_PROCESS),
                ok(DoctorRules.CHECK_BATTERY),
                unknown(DoctorRules.CHECK_NOTIFICATIONS, "NOTIFICATIONS_NOT_MEASURED")
            )
        )

        assertEquals(EmbedState.UNKNOWN, health.state)
        assertEquals(EmbedHealth.PARTLY_UNKNOWN, health.headline)
        assertTrue("an unknown row has no action", health.advice.isEmpty())
    }

    @Test
    fun `a stopped process is stopped and offers a restart`() {
        val health = EmbedHealth.of(report(bad(DoctorRules.CHECK_PROCESS, "PROCESS_STOPPED")))

        assertEquals(EmbedState.STOPPED, health.state)
        assertEquals(EmbedHealth.SERVER_STOPPED, health.headline)
        assertEquals(listOf(EmbedAdvice("PROCESS_STOPPED", EmbedAction.RESTART_SERVER)), health.advice)
    }

    @Test
    fun `a quarantine is its own headline`() {
        val health = EmbedHealth.of(report(bad(DoctorRules.CHECK_PROCESS, "PROCESS_QUARANTINED")))

        assertEquals(EmbedHealth.SERVER_QUARANTINED, health.headline)
        assertEquals(EmbedAction.RESTART_SERVER, health.advice.single().action)
    }

    @Test
    fun `a missing runtime is named and has no button that could fix it`() {
        val health = EmbedHealth.of(report(bad(DoctorRules.CHECK_PROCESS, "PROCESS_NO_RUNTIME")))

        assertEquals(EmbedHealth.RUNTIME_MISSING, health.headline)
        assertEquals(EmbedAction.NONE, health.advice.single().action)
    }

    @Test
    fun `an unclean previous end is reported as died, not stopped`() {
        val health = EmbedHealth.of(
            report(bad(DoctorRules.CHECK_PROCESS, "PROCESS_STOPPED_UNCLEAN"))
        )

        assertEquals(EmbedHealth.SERVER_DIED, health.headline)
        assertEquals(EmbedState.STOPPED, health.state)
    }

    @Test
    fun `a process that was never measured is not described as healthy`() {
        val health = EmbedHealth.of(
            report(unknown(DoctorRules.CHECK_PROCESS, "PROCESS_NOT_RUNNING"))
        )

        assertEquals(EmbedState.STOPPED, health.state)
        assertEquals(EmbedHealth.SERVER_STOPPED, health.headline)
        assertTrue(health.advice.isEmpty())
    }

    @Test
    fun `an unrecognised process state is unknown, not a confident stop`() {
        // The status document exists but names a state these rules do not know.
        // "Stopped" would be a guess about a server that is plainly answering,
        // so the projection must not make that claim.
        val health = EmbedHealth.of(
            report(unknown(DoctorRules.CHECK_PROCESS, "PROCESS_UNKNOWN_STATE"))
        )

        assertEquals(EmbedState.UNKNOWN, health.state)
        assertEquals(EmbedHealth.PARTLY_UNKNOWN, health.headline)
        assertTrue(health.advice.isEmpty())
    }

    @Test
    fun `a starting process is neither healthy nor broken`() {
        val health = EmbedHealth.of(
            report(notice(DoctorRules.CHECK_PROCESS, "PROCESS_STARTING"))
        )

        assertEquals(EmbedState.STARTING, health.state)
        assertEquals(EmbedHealth.SERVER_STARTING, health.headline)
    }

    @Test
    fun `a fault outranks a starting spinner`() {
        val health = EmbedHealth.of(
            report(
                notice(DoctorRules.CHECK_PROCESS, "PROCESS_STARTING"),
                bad(DoctorRules.CHECK_BATTERY, "BATTERY_OPTIMISED")
            )
        )

        assertEquals(EmbedState.ATTENTION, health.state)
        assertEquals(EmbedHealth.NEEDS_ATTENTION, health.headline)
        assertEquals("BATTERY_OPTIMISED", health.advice.single().code)
    }

    @Test
    fun `a deliberate notice is not turned into a to-do`() {
        val health = EmbedHealth.of(
            report(
                ok(DoctorRules.CHECK_PROCESS),
                notice(DoctorRules.CHECK_DATA_PLANE, "DATA_PLANE_OFF"),
                notice(DoctorRules.CHECK_BRIDGE, "BRIDGE_NONE")
            )
        )

        assertEquals(EmbedState.RUNNING, health.state)
        assertTrue("the operator chose the data plane off", health.advice.isEmpty())
    }

    @Test
    fun `battery advice is the first thing an operator is offered`() {
        val health = EmbedHealth.of(
            report(
                ok(DoctorRules.CHECK_PROCESS),
                bad(DoctorRules.CHECK_NOTIFICATIONS, "NOTIFICATIONS_OFF"),
                bad(DoctorRules.CHECK_BATTERY, "BATTERY_OPTIMISED"),
                bad(DoctorRules.CHECK_STANDBY, "STANDBY_RARE")
            )
        )

        assertEquals(EmbedAction.OPEN_BATTERY_SETTINGS, health.advice.first().action)
        assertEquals(listOf("BATTERY_OPTIMISED", "STANDBY_RARE", "NOTIFICATIONS_OFF"),
            health.advice.map { it.code })
    }

    @Test
    fun `advice is capped so the screen stays readable`() {
        val health = EmbedHealth.of(
            report(
                ok(DoctorRules.CHECK_PROCESS),
                bad(DoctorRules.CHECK_BATTERY, "BATTERY_OPTIMISED"),
                bad(DoctorRules.CHECK_BACKGROUND, "BACKGROUND_RESTRICTED"),
                bad(DoctorRules.CHECK_NOTIFICATIONS, "NOTIFICATIONS_OFF"),
                bad(DoctorRules.CHECK_AGENT, "AGENT_CRASHING"),
                bad(DoctorRules.CHECK_DATA_PLANE, "DATA_PLANE_NOT_RUNNING"),
                bad(DoctorRules.CHECK_STORAGE, "STORAGE_LOW")
            )
        )

        assertEquals(EmbedHealth.MAX_ADVICE, health.advice.size)
    }

    @Test
    fun `two rows with one code produce one action`() {
        val health = EmbedHealth.of(
            report(
                ok(DoctorRules.CHECK_PROCESS),
                bad(DoctorRules.CHECK_AGENT, "AGENT_CRASHING"),
                bad(DoctorRules.CHECK_DATA_PLANE, "AGENT_CRASHING")
            )
        )

        assertEquals(1, health.advice.size)
    }

    @Test
    fun `a refused start becomes the one action that clears it`() {
        val refused = EmbedHealth.ofFailure(EmbedConsent.REQUIRED)
        assertEquals(EmbedState.STOPPED, refused.state)
        assertEquals(EmbedConsent.REQUIRED, refused.headline)
        assertEquals(EmbedAction.GRANT_CONSENT, refused.advice.single().action)

        val outdated = EmbedHealth.ofFailure(EmbedConsent.OUTDATED)
        assertEquals(EmbedAction.GRANT_CONSENT, outdated.advice.single().action)

        val busyPort = EmbedHealth.ofFailure("PORT_IN_USE")
        assertEquals(EmbedAction.RETRY, busyPort.advice.single().action)

        val background = EmbedHealth.ofFailure(EmbedHealth.FOREGROUND_START_NOT_ALLOWED)
        assertEquals(EmbedAction.RETRY, background.advice.single().action)
        assertEquals(EmbedHealth.FOREGROUND_START_NOT_ALLOWED, background.headline)
    }

    @Test
    fun `the action table names what the operator can do`() {
        assertEquals(
            EmbedAction.OPEN_NOTIFICATION_SETTINGS,
            EmbedHealth.actionFor("NOTIFICATIONS_OFF")
        )
        assertEquals(
            EmbedAction.OPEN_APP_SETTINGS,
            EmbedHealth.actionFor("BACKGROUND_RESTRICTED")
        )
        assertEquals(EmbedAction.RESTART_SERVER, EmbedHealth.actionFor("PORT_CHANGE_RESTART_REQUIRED"))
        assertEquals(EmbedAction.NONE, EmbedHealth.actionFor("SOMETHING_NEW"))
    }

    @Test
    fun `counts add up to the number of checks`() {
        val health = EmbedHealth.of(
            report(
                ok(DoctorRules.CHECK_PROCESS),
                bad(DoctorRules.CHECK_BATTERY, "BATTERY_OPTIMISED"),
                notice(DoctorRules.CHECK_DATA_PLANE, "DATA_PLANE_OFF"),
                unknown(DoctorRules.CHECK_STORAGE, "STORAGE_NOT_MEASURED")
            )
        )

        assertEquals(4, health.counts.total)
        assertEquals(1, health.counts.ok)
        assertEquals(1, health.counts.bad)
        assertEquals(1, health.counts.notice)
        assertEquals(1, health.counts.unknown)
    }
}
