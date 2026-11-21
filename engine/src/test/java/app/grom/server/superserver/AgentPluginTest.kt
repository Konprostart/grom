// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.http.ApiException
import app.grom.server.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPluginTest {

    private val health = mapOf("state" to "READY", "api" to 1, "pid" to 42L)

    private fun plugin(
        statusExtra: () -> Map<String, Any?> = { emptyMap() },
        exec: (String, String?) -> HttpResponse
    ): AgentPlugin = AgentPlugin({ health }, exec, statusExtra)

    @Test
    fun statusAndHealthAreReadOnlySnapshots() {
        var called = false
        val plugin = plugin { _, _ ->
            called = true
            HttpResponse(500, "{}")
        }
        assertEquals(health, plugin.status())
        val result = plugin.rpc("health", emptyMap())
        assertTrue(result is PluginResult.Ok)
        assertEquals(health, (result as PluginResult.Ok).payload)
        assertFalse(called)
    }

    @Test
    fun statusCarriesTheHostBlocksWhileHealthStaysPure() {
        // The notification block (D65) rides the same statusExtra callback as
        // the reference config (D52), so `status` sees it and `health` does not.
        val notifications = mapOf(
            "enabled" to true,
            "configured" to true,
            "lastDelivery" to mapOf("ok" to true, "atMs" to 5L)
        )
        val plugin = plugin(statusExtra = { mapOf("notifications" to notifications) }) { _, _ ->
            HttpResponse(500, "{}")
        }

        val status = plugin.status()
        assertEquals(notifications, status["notifications"])

        val healthResult = plugin.rpc("health", emptyMap())
        assertTrue(healthResult is PluginResult.Ok)
        assertFalse((healthResult as PluginResult.Ok).payload.containsKey("notifications"))
    }

    @Test
    fun relaysTheOpAndReturnsTheAgentEnvelope() {
        var seenOp: String? = null
        var seenBody: String? = null
        val plugin = plugin { op, body ->
            seenOp = op
            seenBody = body
            HttpResponse(200, """{"op":"ping","ok":true,"result":"pong"}""")
        }

        val result = plugin.rpc("ping", mapOf("x" to 1))
        assertTrue(result is PluginResult.Ok)
        assertEquals("ping", seenOp)
        assertTrue(seenBody!!.contains("\"op\":\"ping\""))
        assertTrue(seenBody!!.contains("\"x\":1"))
        assertEquals(true, (result as PluginResult.Ok).payload["ok"])
        assertEquals("pong", result.payload["result"])
    }

    @Test
    fun agentErrorsKeepTheirStatusAndCode() {
        val plugin = plugin { _, _ ->
            HttpResponse(503, """{"ok":false,"code":"STORAGE_UNAVAILABLE","message":"no sqlite"}""")
        }
        val result = plugin.rpc("kv.get", mapOf("key" to "a"))
        assertTrue(result is PluginResult.Failure)
        val failure = result as PluginResult.Failure
        assertEquals(503, failure.status)
        assertEquals("STORAGE_UNAVAILABLE", failure.code)
        assertEquals("no sqlite", failure.message)
    }

    @Test
    fun serverExceptionsBecomeStructuredFailures() {
        val plugin = plugin { _, _ ->
            throw ApiException(409, "QUARANTINED", "agent is quarantined")
        }
        val result = plugin.rpc("ping", emptyMap())
        assertTrue(result is PluginResult.Failure)
        assertEquals(409, (result as PluginResult.Failure).status)
        assertEquals("QUARANTINED", result.code)
    }

    @Test
    fun nonJsonSuccessIsAProtocolError() {
        val plugin = plugin { _, _ -> HttpResponse(200, "not json") }
        val result = plugin.rpc("ping", emptyMap())
        assertTrue(result is PluginResult.Failure)
        assertEquals(502, (result as PluginResult.Failure).status)
        assertEquals("AGENT_PROTOCOL_ERROR", result.code)
    }

    @Test
    fun unknownOpsNeverReachTheAgent() {
        var called = false
        val plugin = plugin { _, _ ->
            called = true
            HttpResponse(200, "{}")
        }
        val result = plugin.rpc("rm -rf", emptyMap())
        assertTrue(result is PluginResult.Failure)
        assertEquals(400, (result as PluginResult.Failure).status)
        assertFalse(called)
    }

    @Test
    fun statusAddsHostReferenceDataButHealthStaysPure() {
        val extra = mapOf<String, Any?>("npub" to "npub1example", "relays" to listOf<String>())
        val plugin = AgentPlugin(
            health = { health },
            exec = { _, _ -> HttpResponse(500, "{}") },
            statusExtra = { extra }
        )

        assertEquals(health + extra, plugin.status())
        val status = plugin.rpc("status", emptyMap()) as PluginResult.Ok
        assertEquals(health + extra, status.payload)
        // `health` deliberately stays the agent's own answer: no device config.
        val healthOnly = plugin.rpc("health", emptyMap()) as PluginResult.Ok
        assertEquals(health, healthOnly.payload)
    }

    @Test
    fun defaultStatusExtraKeepsTheOriginalContract() {
        val plugin = plugin { _, _ -> HttpResponse(500, "{}") }
        assertEquals(health, plugin.status())
    }
}
