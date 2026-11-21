// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardModelTest {

    private fun status(
        state: String = "READY",
        superRunning: Boolean = true,
        superEnabled: Boolean = true,
        superError: String? = null,
        bridgeConfigured: Boolean = true,
        bridgeConnected: Boolean = true,
        projects: List<Map<String, Any?>> = listOf(
            mapOf("id" to "my-wallet", "title" to "My Wallet", "state" to "alive")
        )
    ): Map<String, Any?> = mapOf(
        "state" to state,
        "port" to 47001L,
        "agentPort" to 47003L,
        "super" to mapOf(
            "enabled" to superEnabled,
            "running" to superRunning,
            "address" to "127.0.0.1:47002",
            "error" to superError,
            "bridge" to mapOf(
                "configured" to bridgeConfigured,
                "connected" to bridgeConnected,
                "active" to if (bridgeConnected) 1 else 0,
                "tunnels" to listOf(
                    mapOf(
                        "id" to "0123456789abcdef",
                        "label" to "Mostro VPS",
                        "state" to if (bridgeConnected) "connected" else "disconnected"
                    )
                )
            ),
            // Configuration written by the operator, never evidence of liveness.
            "remoteBridge" to mapOf("label" to "Configured VPS hint")
        ),
        "projects" to projects
    )

    private fun row(dashboard: Dashboard, id: String): ServingRow =
        dashboard.rows.first { it.id == id }

    @Test
    fun `ready is the only green headline`() {
        assertEquals(Lamp.ON, DashboardModel.build(status()).stateLamp)
        assertEquals(Lamp.OFF, DashboardModel.build(status(state = "MISSING_RUNTIME")).stateLamp)
        assertEquals(Lamp.OFF, DashboardModel.build(status(state = "QUARANTINED")).stateLamp)
        assertEquals(Lamp.OFF, DashboardModel.build(status(state = "STOPPED")).stateLamp)
        assertEquals(Lamp.UNKNOWN, DashboardModel.build(status(state = "STARTING")).stateLamp)
    }

    @Test
    fun `no status is offline and every lamp unknown`() {
        val dashboard = DashboardModel.build(null)
        assertEquals(DashboardModel.STATE_OFFLINE, dashboard.state)
        assertEquals(Lamp.UNKNOWN, dashboard.stateLamp)
        assertEquals(4, dashboard.rows.size)
        assertEquals(setOf(Lamp.UNKNOWN), dashboard.rows.map { it.lamp }.toSet())
    }

    @Test
    fun `a configured but stopped data plane is red, not green`() {
        val dashboard = DashboardModel.build(
            status(superEnabled = true, superRunning = false)
        )
        assertEquals(Lamp.OFF, row(dashboard, "data").lamp)
    }

    @Test
    fun `a disabled data plane is unknown`() {
        val dashboard = DashboardModel.build(
            status(superEnabled = false, superRunning = false)
        )
        assertEquals(Lamp.UNKNOWN, row(dashboard, "data").lamp)
    }

    @Test
    fun `a data plane error is red`() {
        val dashboard = DashboardModel.build(
            status(superError = "BIND_FAILED: BindException")
        )
        assertEquals(Lamp.OFF, row(dashboard, "data").lamp)
    }

    @Test
    fun `bridge is green only when the tunnel is measured connected`() {
        assertEquals(Lamp.ON, row(DashboardModel.build(status()), "bridge").lamp)
        assertEquals(
            Lamp.OFF,
            row(
                DashboardModel.build(status(bridgeConfigured = true, bridgeConnected = false)),
                "bridge"
            ).lamp
        )
        assertEquals(
            Lamp.UNKNOWN,
            row(
                DashboardModel.build(status(bridgeConfigured = false, bridgeConnected = false)),
                "bridge"
            ).lamp
        )
    }

    @Test
    fun `bridge label is shown when connected`() {
        assertEquals("Mostro VPS", row(DashboardModel.build(status()), "bridge").value)
    }

    @Test
    fun `the configured bridge hint never supplies the serving label`() {
        // The hint says "Configured VPS hint"; only the measured tunnel label
        // may appear, so a configured-but-down bridge shows no hint at all.
        val down = DashboardModel.build(status(bridgeConnected = false))
        assertEquals("not connected", row(down, "bridge").value)
        assertEquals(Lamp.OFF, row(down, "bridge").lamp)
    }

    @Test
    fun `the serving row counts more than one alive project`() {
        val many = DashboardModel.build(
            status(
                projects = listOf(
                    mapOf("id" to "my-wallet", "title" to "My Wallet", "state" to "alive"),
                    mapOf("id" to "chat", "title" to "Chat", "state" to "alive"),
                    mapOf("id" to "old", "title" to "Old", "state" to "stale")
                )
            )
        )
        // The model renders the list it is given, so the first entry is shown.
        assertEquals("My Wallet +1", row(many, "project").value)
        assertEquals(Lamp.ON, row(many, "project").lamp)
    }

    @Test
    fun `a stale project is red and no project is unknown`() {
        assertEquals(Lamp.ON, row(DashboardModel.build(status()), "project").lamp)
        assertEquals(
            Lamp.OFF,
            row(
                DashboardModel.build(
                    status(projects = listOf(mapOf("id" to "p", "title" to "P", "state" to "stale")))
                ),
                "project"
            ).lamp
        )
        assertEquals(
            Lamp.UNKNOWN,
            row(DashboardModel.build(status(projects = emptyList())), "project").lamp
        )
    }

    @Test
    fun `the control plane is green while the server answers`() {
        assertEquals(Lamp.ON, row(DashboardModel.build(status()), "control").lamp)
        assertEquals(
            Lamp.OFF,
            row(DashboardModel.build(status(state = "STOPPED")), "control").lamp
        )
    }
}
