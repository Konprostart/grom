// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The Telegram-alerts lamp (D67, D56).
 *
 * The rule under test is the one every lamp follows: configuration can never
 * turn it green. Only a delivery that the provider accepted can, and a
 * rejection turns it red.
 */
class NotifyModelTest {

    private fun block(
        enabled: Boolean,
        configured: Boolean,
        lastOk: Boolean? = null
    ): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "configured" to configured,
        "lastDelivery" to mapOf("ok" to lastOk, "atMs" to 1L)
    )

    @Test
    fun `no status at all is grey, not red`() {
        val row = NotifyModel.row(null)
        assertEquals(AlertState.OFF, row.state)
        assertEquals(Lamp.UNKNOWN, row.lamp)
        assertEquals(R.string.alerts_state_off, row.valueRes)
    }

    @Test
    fun `switched off is red and says off`() {
        val row = NotifyModel.row(block(enabled = false, configured = true, lastOk = true))
        assertEquals(AlertState.OFF, row.state)
        assertEquals(Lamp.OFF, row.lamp)
        assertEquals(R.string.alerts_state_off, row.valueRes)
    }

    @Test
    fun `enabled but unconfigured is a failure, not a green`() {
        val row = NotifyModel.row(block(enabled = true, configured = false))
        assertEquals(AlertState.NOT_CONFIGURED, row.state)
        assertEquals(Lamp.OFF, row.lamp)
        assertEquals(R.string.alerts_state_not_configured, row.valueRes)
        assertNotEquals(0, row.detailRes)
    }

    @Test
    fun `configured and enabled but never sent is grey`() {
        val row = NotifyModel.row(block(enabled = true, configured = true, lastOk = null))
        assertEquals(AlertState.READY, row.state)
        assertEquals("configuration alone must not be green", Lamp.UNKNOWN, row.lamp)
        assertEquals(R.string.alerts_state_ready, row.valueRes)
    }

    @Test
    fun `a delivered message is the only thing that turns it green`() {
        val row = NotifyModel.row(block(enabled = true, configured = true, lastOk = true))
        assertEquals(AlertState.LAST_OK, row.state)
        assertEquals(Lamp.ON, row.lamp)
        assertEquals(R.string.alerts_state_delivered, row.valueRes)
    }

    @Test
    fun `a rejected delivery is red`() {
        val row = NotifyModel.row(block(enabled = true, configured = true, lastOk = false))
        assertEquals(AlertState.LAST_FAILED, row.state)
        assertEquals(Lamp.OFF, row.lamp)
        assertEquals(R.string.alerts_state_failed, row.valueRes)
    }

    @Test
    fun `a status document without the block is treated as off`() {
        val row = NotifyModel.row(emptyMap<String, Any?>())
        assertEquals(AlertState.OFF, row.state)
        assertEquals(Lamp.OFF, row.lamp)
    }

    @Test
    fun `the last delivery map is tolerated when it is malformed`() {
        val row = NotifyModel.row(
            mapOf("enabled" to true, "configured" to true, "lastDelivery" to "garbage")
        )
        assertEquals(AlertState.READY, row.state)
        assertEquals(Lamp.UNKNOWN, row.lamp)
        assertNotNull(row.valueRes)
    }
}
