// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The devices screen (D74).
 *
 * A lamp is green only for a measured `alive`; a stored pairing, a label and a
 * never-checked device stay grey on purpose. These tests pin that, so a future
 * refactor cannot quietly turn "configured" into "connected".
 */
class FleetModelTest {

    private fun peer(state: String?) = mapOf<String, Any?>(
        "nodeId" to "0123456789abcdef",
        "label" to "Pixel 4a",
        "address" to "http://10.0.0.7:47002",
        "state" to state,
        "grantedByMe" to listOf("status.read", "config.read")
    )

    @Test
    fun `only a measured alive peer is green`() {
        assertEquals(Lamp.ON, FleetModel.lampOf(peer("alive")))
        assertEquals(Lamp.OFF, FleetModel.lampOf(peer("unreachable")))
        assertEquals(Lamp.UNKNOWN, FleetModel.lampOf(peer("unknown")))
        assertEquals(Lamp.UNKNOWN, FleetModel.lampOf(peer("stale")))
        assertEquals(Lamp.UNKNOWN, FleetModel.lampOf(null))
    }

    @Test
    fun `a pairing alone never becomes green`() {
        val configured = peer(null)
        assertNotEquals(Lamp.ON, FleetModel.lampOf(configured))
        assertEquals(R.string.fleet_state_unknown, FleetModel.stateRes(null))
    }

    @Test
    fun `every state has a readable label`() {
        assertEquals(R.string.fleet_state_alive, FleetModel.stateRes("alive"))
        assertEquals(R.string.fleet_state_stale, FleetModel.stateRes("stale"))
        assertEquals(R.string.fleet_state_unreachable, FleetModel.stateRes("unreachable"))
        assertEquals(R.string.fleet_state_unknown, FleetModel.stateRes("nonsense"))
    }

    @Test
    fun `a fingerprint is grouped in fours`() {
        assertEquals("0123-4567-89ab-cdef", FleetModel.fingerprint("0123456789abcdef"))
        assertEquals("", FleetModel.fingerprint(null))
        assertEquals("not-a-node", FleetModel.fingerprint("not-a-node"))
    }

    @Test
    fun `the summary counts only measured peers`() {
        assertEquals(
            "1/2",
            FleetModel.summary(listOf(peer("alive"), peer("unreachable")))
        )
        assertEquals("0/0", FleetModel.summary(emptyList()))
    }

    @Test
    fun `scopes are shown and an empty grant is blank`() {
        assertEquals("status.read, config.read", FleetModel.scopesText(peer("alive")))
        assertEquals("", FleetModel.scopesText(peer("alive").minus("grantedByMe")))
        assertEquals("", FleetModel.scopesText(null))
    }

    @Test
    fun `a one-way grant is marked as inbound and can never be green`() {
        val inbound = peer("unknown").minus("address")
        assertEquals(true, FleetModel.isInboundOnly(inbound))
        assertEquals(false, FleetModel.isInboundOnly(peer("alive")))
        assertEquals(true, FleetModel.isInboundOnly(null))

        // A key that only calls in has nothing to probe, so the lamp stays grey
        // even though the grant itself is valid (D77).
        assertNotEquals(Lamp.ON, FleetModel.lampOf(inbound))
        assertEquals(Lamp.UNKNOWN, FleetModel.lampOf(inbound))
    }
}
