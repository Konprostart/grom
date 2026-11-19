// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChannelTest {

    @Test
    fun `parses the channel line`() {
        val ready = AgentChannel.observe("GROM_AGENT_READY 4242 51234")
        assertEquals(4242L, ready?.pid)
        assertEquals(51234, ready?.port)
    }

    @Test
    fun `tolerates extra whitespace and padding`() {
        val ready = AgentChannel.observe("  GROM_AGENT_READY\t9\t1024  ")
        assertEquals(9L, ready?.pid)
        assertEquals(1024, ready?.port)
    }

    @Test
    fun `does not match the plain health line`() {
        assertNull(AgentChannel.observe("GROM_READY 4242"))
        assertNull(AgentChannel.observe("GROM_FATAL boom"))
    }

    @Test
    fun `rejects missing, non-numeric and out-of-range ports`() {
        assertNull(AgentChannel.observe("GROM_AGENT_READY 1"))
        assertNull(AgentChannel.observe("GROM_AGENT_READY 1 abc"))
        assertNull(AgentChannel.observe("GROM_AGENT_READY 1 0"))
        assertNull(AgentChannel.observe("GROM_AGENT_READY 1 70000"))
        assertNull(AgentChannel.observe(null))
    }

    @Test
    fun `pid must equal the spawned child`() {
        val ready = AgentChannel.observe("GROM_AGENT_READY 7 9000")
        assertTrue(AgentChannel.matches(ready, 7))
        assertFalse(AgentChannel.matches(ready, 8))
        assertFalse(AgentChannel.matches(ready, -1))
        assertFalse(AgentChannel.matches(null, 7))
    }

    @Test
    fun `the shared op list is the documented whitelist`() {
        assertEquals(listOf("ping", "echo", "status", "kv.get", "kv.put"), AgentChannel.OPS.toList())
    }
}
