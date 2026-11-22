// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

import app.grom.server.http.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The serialised Doctor report (D80, D81).
 *
 * The report exists so an operator can paste it somewhere; therefore the test
 * asserts two things that matter more than the shape: the document is an
 * allow-list (a field that is not named in [DoctorDocument] never appears) and the
 * text contains no device state that is not a measurement.
 */
class DoctorReportTest {

    private val facts = DoctorFacts(
        batteryExempt = false,
        backgroundRestricted = true,
        standbyBucket = DoctorRules.BUCKET_RARE,
        notificationsEnabled = true,
        deviceIdle = true,
        batteryPercent = 41,
        charging = false,
        freeBytes = 1024L,
        network = "mobile",
        metered = true,
        serverState = "READY",
        crashes = 2,
        restarts = 5,
        dataPlaneEnabled = true,
        dataPlaneRunning = false,
        bridgeConfigured = true,
        bridgeConnected = false,
        tunnelsConnected = 0,
        lastUnexpectedStopMs = 1_700_000_000_000L,
        unexpectedStops = 3,
        previousSessionStartedAtMs = 1_699_999_000_000L,
        lastExitReason = "LOW_MEMORY",
        lastExitAtMs = 1_699_999_500_000L,
        sdkInt = 34,
        measuredAtMs = 1_700_000_000_000L
    )

    private val report = DoctorRules.evaluate(facts)

    @Test
    fun `the status block carries exactly the three agreed fields`() {
        val block = DoctorDocument.statusBlock(facts)
        assertEquals(
            setOf("batteryExempt", "backgroundRestricted", "lastUnexpectedStopMs"),
            block.keys
        )
        assertEquals(false, block["batteryExempt"])
        assertEquals(true, block["backgroundRestricted"])
        assertEquals(1_700_000_000_000L, block["lastUnexpectedStopMs"])
    }

    @Test
    fun `a missing unexpected stop is zero in the status block, not null`() {
        val block = DoctorDocument.statusBlock(DoctorFacts())
        assertEquals(0L, block["lastUnexpectedStopMs"])
    }

    @Test
    fun `the document names the verdict, the checks and the facts`() {
        val document = DoctorDocument.document(report)
        assertEquals("ATTENTION", document["verdict"])
        assertEquals(1_700_000_000_000L, document["measuredAtMs"])

        val checks = document["checks"] as List<*>
        assertEquals(DoctorRules.ORDER.size, checks.size)
        val first = checks.first() as Map<*, *>
        assertEquals(DoctorRules.CHECK_PROCESS, first["id"])
        assertEquals("OK", first["level"])

        @Suppress("UNCHECKED_CAST")
        val encoded = document["facts"] as Map<String, Any?>
        assertEquals(34, encoded["sdkInt"])
        assertEquals("LOW_MEMORY", encoded["lastExitReason"])
        assertEquals(0, encoded["tunnelsConnected"])
    }

    @Test
    fun `counts add up to the number of checks`() {
        @Suppress("UNCHECKED_CAST")
        val counts = DoctorDocument.document(report)["counts"] as Map<String, Any?>
        val total = (counts["ok"] as Int) + (counts["bad"] as Int) +
            (counts["notice"] as Int) + (counts["unknown"] as Int)
        assertEquals(DoctorRules.ORDER.size, total)
    }

    @Test
    fun `the json parses and keeps the allow-listed shape`() {
        val parsed = JsonParser.parseObjectOrNull(DoctorDocument.json(report))
        assertNotNull(parsed)
        assertEquals("ATTENTION", parsed!!["verdict"])
        assertTrue(parsed.containsKey("checks"))
        assertTrue(parsed.containsKey("facts"))
    }

    @Test
    fun `no secret or device identifier can appear in the document`() {
        val json = DoctorDocument.json(report)
        // Identifiers that exist elsewhere in Grom and must never reach here.
        for (banned in listOf("token", "chatId", "passwordHash", "seed", "address", "host",
            "username", "nodeId", "peer", "publicKey", "secret")) {
            assertFalse("the report must not carry '$banned'", json.contains(banned, ignoreCase = true))
        }
    }

    @Test
    fun `the document is the whole paste payload and carries no free text`() {
        // "Copy diagnostics" copies exactly this JSON, so the text it produces is
        // the paste payload and it must stay free of the platform's own sentences.
        val json = DoctorDocument.json(report)
        assertTrue(json.contains("LOW_MEMORY"))
        assertTrue(json.contains("batteryExempt"))
        assertFalse(json.contains("optimised"))
        // A report with no measurements is still a valid document.
        val empty = DoctorDocument.json(DoctorRules.evaluate(DoctorFacts()))
        assertTrue(empty.contains("NOTHING_MEASURED"))
        assertTrue(empty.contains("\"batteryExempt\":null"))
    }
}
