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

    @Test
    fun statusAndHealthAreReadOnlySnapshots() {
        var called = false
        val plugin = AgentPlugin({ health }) { _, _ ->
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
    fun relaysTheOpAndReturnsTheAgentEnvelope() {
        var seenOp: String? = null
        var seenBody: String? = null
        val plugin = AgentPlugin({ health }) { op, body ->
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
        val plugin = AgentPlugin({ health }) { _, _ ->
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
        val plugin = AgentPlugin({ health }) { _, _ ->
            throw ApiException(409, "QUARANTINED", "agent is quarantined")
        }
        val result = plugin.rpc("ping", emptyMap())
        assertTrue(result is PluginResult.Failure)
        assertEquals(409, (result as PluginResult.Failure).status)
        assertEquals("QUARANTINED", result.code)
    }

    @Test
    fun nonJsonSuccessIsAProtocolError() {
        val plugin = AgentPlugin({ health }) { _, _ -> HttpResponse(200, "not json") }
        val result = plugin.rpc("ping", emptyMap())
        assertTrue(result is PluginResult.Failure)
        assertEquals(502, (result as PluginResult.Failure).status)
        assertEquals("AGENT_PROTOCOL_ERROR", result.code)
    }

    @Test
    fun unknownOpsNeverReachTheAgent() {
        var called = false
        val plugin = AgentPlugin({ health }) { _, _ ->
            called = true
            HttpResponse(200, "{}")
        }
        val result = plugin.rpc("rm -rf", emptyMap())
        assertTrue(result is PluginResult.Failure)
        assertEquals(400, (result as PluginResult.Failure).status)
        assertFalse(called)
    }
}
