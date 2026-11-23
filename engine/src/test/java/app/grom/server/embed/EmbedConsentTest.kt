// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Consent that is about a specific notice text (D88).
 *
 * The bug this prevents is subtle: a host records "yes" once, later changes what
 * the server does (or what the dialog says), and keeps starting it under the old
 * answer. Versioning the notice makes the stored answer a statement about text.
 * A host that never versions keeps working exactly as before.
 */
class EmbedConsentTest {

    @Test
    fun `nothing recorded is a refusal, not an implicit yes`() {
        assertEquals(EmbedConsent.REQUIRED, EmbedConsent.refusal(null, ""))
        assertEquals(EmbedConsent.REQUIRED, EmbedConsent.refusal(null, "3"))
    }

    @Test
    fun `an unversioned host keeps working`() {
        val record = EmbedConsent.Record("", grantedAtMs = 1_000)
        assertNull(EmbedConsent.refusal(record, ""))
        assertNull(EmbedConsent.refusal(record, "   "))
    }

    @Test
    fun `the same notice version is still covered`() {
        val record = EmbedConsent.Record("2", grantedAtMs = 1_000)
        assertNull(EmbedConsent.refusal(record, "2"))
        assertNull("the host may pad its version", EmbedConsent.refusal(record, " 2 "))
    }

    @Test
    fun `a newer notice needs a new answer`() {
        val record = EmbedConsent.Record("2", grantedAtMs = 1_000)
        assertEquals(EmbedConsent.OUTDATED, EmbedConsent.refusal(record, "3"))
    }

    @Test
    fun `starting to version an unversioned consent asks once more`() {
        val record = EmbedConsent.Record("", grantedAtMs = 1_000)
        assertEquals(EmbedConsent.OUTDATED, EmbedConsent.refusal(record, "1"))
    }

    @Test
    fun `a denied answer is not a record at all`() {
        assertNull(EmbedConsent.record(granted = false, noticeVersion = "1", grantedAtMs = 5_000))
        assertEquals(
            EmbedConsent.REQUIRED,
            EmbedConsent.refusal(
                EmbedConsent.record(granted = false, noticeVersion = "1", grantedAtMs = 5_000),
                "1"
            )
        )
    }

    @Test
    fun `a granted answer keeps the version it was granted for`() {
        val record = EmbedConsent.record(granted = true, noticeVersion = " 4 ", grantedAtMs = 7_000)
        assertEquals(EmbedConsent.Record("4", 7_000), record)
    }
}
