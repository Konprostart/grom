// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureLimiterTest {

    private var now = 1_000_000L
    private fun limiter() = FailureLimiter(maxFailures = 20, windowMs = 60_000) { now }

    @Test
    fun `starts unblocked`() {
        assertFalse(limiter().isBlocked())
    }

    @Test
    fun `blocks at the threshold`() {
        val subject = limiter()
        repeat(20) { subject.noteFailure() }
        assertTrue(subject.isBlocked())
    }

    @Test
    fun `nineteen failures are not enough`() {
        val subject = limiter()
        repeat(19) { subject.noteFailure() }
        assertFalse(subject.isBlocked())
        assertEquals(19, subject.failureCount())
    }

    @Test
    fun `success clears the counter`() {
        val subject = limiter()
        repeat(20) { subject.noteFailure() }
        subject.noteSuccess()
        assertFalse(subject.isBlocked())
        assertEquals(0, subject.failureCount())
    }

    @Test
    fun `window expiry unblocks`() {
        val subject = limiter()
        repeat(20) { subject.noteFailure() }
        assertTrue(subject.isBlocked())
        now += 61_000
        assertFalse(subject.isBlocked())
    }

    @Test
    fun `old failures are pruned individually`() {
        val subject = limiter()
        repeat(10) { subject.noteFailure() }
        now += 61_000
        repeat(10) { subject.noteFailure() }
        assertEquals(10, subject.failureCount())
        assertFalse(subject.isBlocked())
    }
}
