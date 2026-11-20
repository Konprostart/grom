// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    private var now = 1_000_000L

    private fun limiter(
        capacity: Int = 5,
        refillPerSecond: Double = 1.0,
        maxClients: Int = 1024
    ): RateLimiter = RateLimiter(
        capacity = capacity,
        refillPerSecond = refillPerSecond,
        maxClients = maxClients,
        clock = { now }
    )

    @Test
    fun `burst is allowed up to capacity`() {
        val limiter = limiter(capacity = 5, refillPerSecond = 1.0)
        repeat(5) { index ->
            assertTrue("request $index must be allowed", limiter.check("10.0.0.1").allowed)
        }
        assertFalse(limiter.check("10.0.0.1").allowed)
    }

    @Test
    fun `blocked decision carries a sane retry delay`() {
        val limiter = limiter(capacity = 1, refillPerSecond = 2.0)
        assertTrue(limiter.check("10.0.0.1").allowed)
        val blocked = limiter.check("10.0.0.1")
        assertFalse(blocked.allowed)
        assertTrue(blocked.retryAfterSeconds in 1..3600)
        assertEquals(1, blocked.retryAfterSeconds)
    }

    @Test
    fun `retry delay is rounded up and bounded`() {
        val slow = limiter(capacity = 1, refillPerSecond = 0.25)
        assertTrue(slow.check("10.0.0.1").allowed)
        assertEquals(4, slow.check("10.0.0.1").retryAfterSeconds)

        val verySlow = limiter(capacity = 1, refillPerSecond = 0.0001)
        assertTrue(verySlow.check("10.0.0.2").allowed)
        assertEquals(3600, verySlow.check("10.0.0.2").retryAfterSeconds)
    }

    @Test
    fun `tokens refill over time`() {
        val limiter = limiter(capacity = 3, refillPerSecond = 1.0)
        repeat(3) { assertTrue(limiter.check("10.0.0.1").allowed) }
        assertFalse(limiter.check("10.0.0.1").allowed)

        now += 2_000L
        assertTrue(limiter.check("10.0.0.1").allowed)
        assertTrue(limiter.check("10.0.0.1").allowed)
        assertFalse(limiter.check("10.0.0.1").allowed)
    }

    @Test
    fun `refill never exceeds capacity`() {
        val limiter = limiter(capacity = 2, refillPerSecond = 1.0)
        now += 60_000L
        assertTrue(limiter.check("10.0.0.1").allowed)
        assertTrue(limiter.check("10.0.0.1").allowed)
        assertFalse(limiter.check("10.0.0.1").allowed)
    }

    @Test
    fun `a backwards clock adds no tokens`() {
        val limiter = limiter(capacity = 1, refillPerSecond = 1.0)
        assertTrue(limiter.check("10.0.0.1").allowed)

        now += 5_000L
        assertTrue(limiter.check("10.0.0.1").allowed)
        assertFalse(limiter.check("10.0.0.1").allowed)

        now -= 10_000L
        assertFalse(limiter.check("10.0.0.1").allowed)
    }

    @Test
    fun `clients have independent buckets`() {
        val limiter = limiter(capacity = 1, refillPerSecond = 1.0)
        assertTrue(limiter.check("10.0.0.1").allowed)
        assertFalse(limiter.check("10.0.0.1").allowed)
        assertTrue(limiter.check("10.0.0.2").allowed)
    }

    @Test
    fun `tracked clients are bounded`() {
        val limiter = limiter(capacity = 5, refillPerSecond = 1.0, maxClients = 3)
        repeat(20) { index -> limiter.check("10.0.0.$index") }
        assertTrue(limiter.size() <= 3)
        assertEquals(3, limiter.size())
    }

    @Test
    fun `allowed decision carries no retry delay`() {
        val limiter = limiter(capacity = 4, refillPerSecond = 1.0)
        val decision = limiter.check("10.0.0.1")
        assertTrue(decision.allowed)
        assertEquals(0, decision.retryAfterSeconds)
    }
}

class LoginLimiterTest {

    private var now = 1_000_000L

    private fun limiter(
        maxFailures: Int = 3,
        windowMs: Long = 60_000L,
        lockoutMs: Long = 300_000L,
        maxClients: Int = 1024
    ): LoginLimiter = LoginLimiter(
        maxFailures = maxFailures,
        windowMs = windowMs,
        lockoutMs = lockoutMs,
        maxClients = maxClients,
        clock = { now }
    )

    @Test
    fun `lockout starts after maxFailures`() {
        val limiter = limiter(maxFailures = 3)

        assertFalse(limiter.noteFailure("10.0.0.1").locked)
        assertFalse(limiter.check("10.0.0.1").locked)
        assertFalse(limiter.noteFailure("10.0.0.1").locked)

        val locked = limiter.noteFailure("10.0.0.1")
        assertTrue(locked.locked)
        assertEquals(300, locked.retryAfterSeconds)
        assertTrue(limiter.check("10.0.0.1").locked)
    }

    @Test
    fun `lockout countdown shrinks and then expires`() {
        val limiter = limiter(maxFailures = 1, lockoutMs = 300_000L)
        assertTrue(limiter.noteFailure("10.0.0.1").locked)

        now += 100_000L
        val status = limiter.check("10.0.0.1")
        assertTrue(status.locked)
        assertEquals(200, status.retryAfterSeconds)

        now += 200_000L
        assertFalse(limiter.check("10.0.0.1").locked)
    }

    @Test
    fun `success clears failures and lockout`() {
        val limiter = limiter(maxFailures = 3)

        limiter.noteFailure("10.0.0.1")
        limiter.noteFailure("10.0.0.1")
        limiter.noteSuccess("10.0.0.1")

        assertFalse(limiter.check("10.0.0.1").locked)
        assertFalse(limiter.noteFailure("10.0.0.1").locked)
        assertFalse(limiter.noteFailure("10.0.0.1").locked)
    }

    @Test
    fun `failures outside the window are forgotten`() {
        val limiter = limiter(maxFailures = 3, windowMs = 1_000L)

        limiter.noteFailure("10.0.0.1")
        limiter.noteFailure("10.0.0.1")

        now += 1_001L
        assertFalse(limiter.noteFailure("10.0.0.1").locked)
        assertFalse(limiter.noteFailure("10.0.0.1").locked)
    }

    @Test
    fun `clients are isolated from each other`() {
        val limiter = limiter(maxFailures = 2)
        assertFalse(limiter.noteFailure("10.0.0.1").locked)
        assertTrue(limiter.noteFailure("10.0.0.1").locked)
        assertFalse(limiter.check("10.0.0.2").locked)
        assertFalse(limiter.noteFailure("10.0.0.2").locked)
    }

    @Test
    fun `noteSuccess on an unknown client is harmless`() {
        val limiter = limiter()
        limiter.noteSuccess("10.0.0.9")
        assertFalse(limiter.check("10.0.0.9").locked)
    }

    @Test
    fun `tracked clients stay bounded`() {
        val limiter = limiter(maxFailures = 2, maxClients = 2)
        repeat(10) { index ->
            limiter.noteFailure("10.0.0.$index")
            limiter.noteFailure("10.0.0.$index")
        }
        // The map is bounded by maxClients; the most recent clients are locked.
        assertTrue(limiter.check("10.0.0.9").locked)
    }
}
