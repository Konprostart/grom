// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthGateTest {

    @Test
    fun `ready line is parsed`() {
        val observation = HealthGate.observe("GROM_READY 4242")
        assertTrue(observation is HealthGate.Observation.Ready)
        assertEquals(4242L, (observation as HealthGate.Observation.Ready).pid)
    }

    @Test
    fun `ready line tolerates whitespace and trailing noise`() {
        assertEquals(
            7L,
            (HealthGate.observe("  GROM_READY\t7  ") as HealthGate.Observation.Ready).pid
        )
    }

    @Test
    fun `a non numeric pid is not a ready line`() {
        assertTrue(HealthGate.observe("GROM_READY abc") is HealthGate.Observation.Unknown)
    }

    @Test
    fun `fatal line is parsed`() {
        val observation = HealthGate.observe("GROM_FATAL cannot bind 127.0.0.1:47001")
        assertEquals(
            "cannot bind 127.0.0.1:47001",
            (observation as HealthGate.Observation.Fatal).message
        )
    }

    @Test
    fun `ordinary log lines are unknown`() {
        assertTrue(HealthGate.observe("listening on 127.0.0.1:47001") is HealthGate.Observation.Unknown)
        assertTrue(HealthGate.observe("") is HealthGate.Observation.Unknown)
        assertTrue(HealthGate.observe(null) is HealthGate.Observation.Unknown)
    }

    @Test
    fun `pid must match the spawned child`() {
        val ready = HealthGate.observe("GROM_READY 100")
        assertTrue(HealthGate.pidMatches(ready, 100))
        assertFalse(HealthGate.pidMatches(ready, 101))
        assertFalse(HealthGate.pidMatches(HealthGate.Observation.Unknown, 100))
    }
}
