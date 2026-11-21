// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorPolicyTest {

    private var now = 1_000_000L
    private fun policy(config: SupervisorConfig = SupervisorConfig()) =
        SupervisorPolicy(config) { now }

    @Test
    fun `starts stopped`() {
        assertEquals(AgentState.STOPPED, policy().state)
    }

    @Test
    fun `missing runtime is reported instead of starting`() {
        val subject = policy()
        assertEquals(AgentState.MISSING_RUNTIME, subject.startRequested(runtimePresent = false))
    }

    @Test
    fun `start followed by ready reaches READY`() {
        val subject = policy()
        assertEquals(AgentState.STARTING, subject.startRequested(true))
        assertEquals(AgentState.READY, subject.onReady())
    }

    @Test
    fun `a late ready line cannot resurrect a stopped agent`() {
        val subject = policy()
        subject.startRequested(true)
        subject.onStopped()
        assertEquals(AgentState.STOPPED, subject.onReady())
    }

    @Test
    fun `one crash goes to BACKOFF`() {
        val subject = policy()
        subject.startRequested(true)
        subject.onReady()
        assertEquals(AgentState.BACKOFF, subject.onCrash("exited"))
        assertEquals(1, subject.crashCount())
    }

    @Test
    fun `three crashes quarantine the agent`() {
        val subject = policy()
        subject.startRequested(true)
        subject.onCrash("one")
        subject.startRequested(true)
        subject.onCrash("two")
        subject.startRequested(true)
        val state = subject.onCrash("three")
        assertEquals(AgentState.QUARANTINED, state)
        assertEquals(3, subject.crashCount())
        assertEquals("three", subject.quarantineReason)
    }

    @Test
    fun `quarantine blocks further starts`() {
        val subject = policy()
        repeat(3) {
            subject.startRequested(true)
            subject.onCrash("crash")
        }
        assertTrue(subject.isQuarantined())
        assertEquals(AgentState.QUARANTINED, subject.startRequested(true))
        assertEquals(3, subject.restarts)
    }

    @Test
    fun `crashes outside the window are forgotten`() {
        val subject = policy()
        subject.startRequested(true)
        subject.onCrash("old")
        now += 11 * 60_000L
        subject.startRequested(true)
        subject.onCrash("new")
        assertEquals(1, subject.crashCount())
        assertFalse(subject.isQuarantined())
    }

    @Test
    fun `reset clears quarantine and counters`() {
        val subject = policy()
        repeat(3) {
            subject.startRequested(true)
            subject.onCrash("crash")
        }
        subject.reset()
        assertEquals(AgentState.STOPPED, subject.state)
        assertEquals(0, subject.crashCount())
        assertNull(subject.quarantineReason)
        assertNull(subject.lastCrashAt)
    }

    @Test
    fun `backoff grows with the crash count and is capped`() {
        val subject = policy()
        subject.startRequested(true)
        subject.onCrash("one")
        assertEquals(1_000L, subject.backoffMs())
        subject.startRequested(true)
        subject.onCrash("two")
        assertEquals(2_000L, subject.backoffMs())
    }

    @Test
    fun `start timeout is detected`() {
        val subject = policy(SupervisorConfig(startTimeoutMs = 5_000))
        subject.startRequested(true)
        assertFalse(subject.startTimedOut())
        now += 5_000
        assertTrue(subject.startTimedOut())
    }

    @Test
    fun `fatal quarantines immediately without consuming retries`() {
        val subject = policy()
        subject.startRequested(true)
        assertEquals(AgentState.QUARANTINED, subject.onFatal("bad config"))
        assertEquals(0, subject.crashCount())
        assertTrue(subject.quarantineReason!!.startsWith("fatal:"))
    }

    @Test
    fun `a long healthy run forgives one old crash`() {
        val subject = policy(SupervisorConfig(healthyResetMs = 60_000))
        subject.startRequested(true)
        subject.onCrash("one")
        subject.startRequested(true)
        subject.onReady()
        assertEquals(1, subject.crashCount())
        now += 61_000
        subject.onHealthyTick()
        assertEquals(0, subject.crashCount())
    }

    @Test
    fun `healthy tick does nothing when not ready`() {
        val subject = policy()
        subject.startRequested(true)
        subject.onCrash("one")
        subject.onHealthyTick()
        assertEquals(1, subject.crashCount())
    }

    @Test
    fun `ready requires starting first`() {
        val subject = policy()
        assertEquals(AgentState.STOPPED, subject.onReady())
    }
}
