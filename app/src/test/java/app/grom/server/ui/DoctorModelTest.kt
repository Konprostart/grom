// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Doctor screen's mapping (D80).
 *
 * The engine decided the levels; this test fixes what the screen does with
 * them. The rules that matter: a lamp is green only for `OK`, `unknown` and
 * `notice` are grey, an unknown code degrades to the level word instead of
 * showing a raw constant, and a missing document leaves every row on screen.
 */
class DoctorModelTest {

    private fun document(vararg checks: Triple<String, String, String?>): Map<String, Any?> =
        mapOf(
            "verdict" to "ATTENTION",
            "measuredAtMs" to 1_000L,
            "checks" to checks.map { (id, level, code) ->
                mapOf("id" to id, "level" to level, "code" to code)
            },
            "facts" to mapOf(
                "sdkInt" to 34,
                "batteryExempt" to false,
                "batteryPercent" to 41,
                "charging" to false,
                "deviceIdle" to true,
                "network" to "wifi",
                "metered" to false,
                "freeBytes" to (3L * 1024L * 1024L * 1024L),
                "unexpectedStops" to 2,
                "lastUnexpectedStopMs" to 1_000L
            )
        )

    @Test
    fun `every row is rendered in the documented order`() {
        val rows = DoctorModel.rows(document())
        assertEquals(DoctorModel.ORDER, rows.map { it.id })
        assertEquals(10, rows.size)
    }

    @Test
    fun `a missing document leaves every row grey and unknown`() {
        val rows = DoctorModel.rows(null)
        assertEquals(10, rows.size)
        assertTrue(rows.all { it.lamp == Lamp.UNKNOWN })
        assertTrue(rows.all { it.detailRes == 0 })
        assertEquals(0, DoctorModel.attentionCount(null))
        assertEquals(0, DoctorModel.unknownCount(null))
    }

    @Test
    fun `only ok is green and only bad is red`() {
        val rows = DoctorModel.rows(
            document(
                Triple("process", "OK", null),
                Triple("battery", "BAD", "BATTERY_OPTIMISED"),
                Triple("standby", "NOTICE", "STANDBY_FREQUENT"),
                Triple("storage", "UNKNOWN", "STORAGE_NOT_MEASURED")
            )
        ).associateBy { it.id }

        assertEquals(Lamp.ON, rows.getValue("process").lamp)
        assertEquals(Lamp.OFF, rows.getValue("battery").lamp)
        // A notice and an unknown are both grey: neither is health.
        assertEquals(Lamp.UNKNOWN, rows.getValue("standby").lamp)
        assertEquals(Lamp.UNKNOWN, rows.getValue("storage").lamp)
    }

    @Test
    fun `a known code becomes a sentence and an unknown one does not`() {
        val rows = DoctorModel.rows(
            document(
                Triple("battery", "BAD", "BATTERY_OPTIMISED"),
                Triple("storage", "BAD", "SOMETHING_FROM_A_NEWER_ENGINE")
            )
        ).associateBy { it.id }

        assertEquals(
            app.grom.server.R.string.doctor_code_battery_optimised,
            rows.getValue("battery").detailRes
        )
        // Degrades to the level word: no raw constant, no empty line.
        assertEquals(0, rows.getValue("storage").detailRes)
        assertEquals(
            app.grom.server.R.string.doctor_level_bad,
            rows.getValue("storage").valueRes
        )
    }

    @Test
    fun `the verdict never turns green on an unknown verdict`() {
        assertEquals(Lamp.ON, DoctorModel.verdictLamp("PASS"))
        assertEquals(Lamp.OFF, DoctorModel.verdictLamp("ATTENTION"))
        assertEquals(Lamp.UNKNOWN, DoctorModel.verdictLamp("PARTLY_UNKNOWN"))
        assertEquals(Lamp.UNKNOWN, DoctorModel.verdictLamp("NOTHING_MEASURED"))
        assertEquals(Lamp.UNKNOWN, DoctorModel.verdictLamp(null))
        assertEquals(Lamp.UNKNOWN, DoctorModel.verdictLamp("SOMETHING_NEW"))
        assertNotEquals(Lamp.ON, DoctorModel.verdictLamp(null))
    }

    @Test
    fun `the counts match the document`() {
        val doc = document(
            Triple("process", "OK", null),
            Triple("battery", "BAD", "BATTERY_OPTIMISED"),
            Triple("standby", "BAD", "STANDBY_RARE"),
            Triple("storage", "UNKNOWN", "STORAGE_NOT_MEASURED")
        )
        assertEquals(2, DoctorModel.attentionCount(doc))
        assertEquals(1, DoctorModel.unknownCount(doc))
    }

    @Test
    fun `the right now lines carry measured values and no lamps`() {
        val facts = DoctorModel.facts(document(), "unknown")
        assertEquals(9, facts.size)
        val values = facts.map { it.valueText }
        assertTrue("doze should be yes", values.contains("yes"))
        assertTrue("battery should be formatted", values.contains("41% · not charging"))
        assertTrue("network should name the transport", values.contains("wifi · unmetered"))
        assertTrue("storage should be human", values.contains("3.0 GB"))
        assertTrue("android should be named", values.contains("34"))
        // FactsRow has no lamp field at all, which is asserted by construction.
        assertTrue(facts.all { it.labelRes != 0 })
    }

    @Test
    fun `a missing fact says unknown rather than a zero`() {
        val doc = mapOf("facts" to mapOf<String, Any?>())
        val values = DoctorModel.facts(doc, "unknown").map { it.valueText }
        assertTrue(values.isNotEmpty())
        assertTrue("nothing may be presented as a measured zero", values.all { it == "unknown" })
    }

    @Test
    fun `no document means no fact lines, not zeros`() {
        assertTrue(DoctorModel.facts(null, "unknown").isEmpty())
    }

    @Test
    fun `bytes are formatted in binary units`() {
        assertEquals("unknown", DoctorModel.bytesText(null, "unknown"))
        assertEquals("512 MB", DoctorModel.bytesText(512L * 1024L * 1024L, "unknown"))
        assertEquals("1.5 GB", DoctorModel.bytesText(1536L * 1024L * 1024L, "unknown"))
        // A negative value is a failed measurement, not a tiny disk.
        assertEquals("unknown", DoctorModel.bytesText(-1L, "unknown"))
    }

    @Test
    fun `thermal constants are named`() {
        assertEquals("none", DoctorModel.thermalText(0, "unknown"))
        assertEquals("moderate", DoctorModel.thermalText(2, "unknown"))
        assertEquals("shutdown", DoctorModel.thermalText(6, "unknown"))
        assertEquals("unknown", DoctorModel.thermalText(null, "unknown"))
        assertEquals("status 7", DoctorModel.thermalText(7, "unknown"))
    }

    @Test
    fun `relative age is coarse and never negative`() {
        val now = 1_000_000_000_000L
        assertEquals("just now", DoctorModel.relativeAge(now - 10_000L, "unknown", now))
        assertEquals("5 min ago", DoctorModel.relativeAge(now - 5L * 60_000L, "unknown", now))
        assertEquals("2 h ago", DoctorModel.relativeAge(now - 2L * 3_600_000L, "unknown", now))
        assertEquals("3 d ago", DoctorModel.relativeAge(now - 3L * 86_400_000L, "unknown", now))
        assertEquals("unknown", DoctorModel.relativeAge(0L, "unknown", now))
        assertEquals("unknown", DoctorModel.relativeAge(now + 10_000L, "unknown", now))
    }

    @Test
    fun `every check id has its own title`() {
        val titles = DoctorModel.ORDER.map { DoctorModel.titleRes(it) }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals(
            app.grom.server.R.string.doctor_check_unknown,
            DoctorModel.titleRes("not_a_check")
        )
    }

    @Test
    fun `every verdict has its own sentence`() {
        val verdicts = listOf("PASS", "ATTENTION", "PARTLY_UNKNOWN", "NOTHING_MEASURED", null)
        val res = verdicts.map { DoctorModel.verdictRes(it) }
        assertEquals(res.size, res.toSet().size)
    }

    @Test
    fun `unexpected stops show a count and a coarse age`() {
        val facts = DoctorModel.facts(document(), "unknown")
        val line = facts.first { it.labelRes == app.grom.server.R.string.doctor_fact_unexpected_stops }
        assertTrue(line.valueText.startsWith("2"))
    }

    /**
     * The screen keeps its own copy of the check ids so it stays pure. That copy
     * is only safe if something notices when the engine's vocabulary changes:
     * a new engine check whose id the screen does not know would render as
     * "unknown", and a renamed id would lose its row entirely.
     */
    @Test
    fun `the screen's ids and order match the engine rules`() {
        assertEquals(app.grom.server.doctor.DoctorRules.ORDER, DoctorModel.ORDER)
        for (id in app.grom.server.doctor.DoctorRules.ORDER) {
            assertNotEquals(
                app.grom.server.R.string.doctor_check_unknown,
                DoctorModel.titleRes(id)
            )
        }
    }
}
