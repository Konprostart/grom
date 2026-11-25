// SPDX-License-Identifier: Apache-2.0
package app.grom.examples.connect

import app.grom.sdk.connect.GromConnectClient
import app.grom.sdk.connect.GromCredential
import app.grom.sdk.connect.GromHttpRequest
import app.grom.sdk.connect.GromHttpResponse
import app.grom.sdk.connect.GromTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the documented example runs (D93).
 *
 * The transport is a fake, but the flow is the real one: discovery, capability
 * check, service listing, status and one published RPC. If the SDK's request
 * shape or parsing changed, this example fails with it.
 */
class ConnectExampleTest {

    private val connectPath = "/grom/v1/connect"
    private val statusPath = "/v1/status"
    private val pluginsPath = "/v1/plugins"
    private val rpcPath = "/v1/plugins/agent/rpc"

    private val connectDocument = """
        {
          "ok": true, "schema": "grom-connect-v1", "service": "grom-super",
          "grom": "0.16.0-server29", "api": 1,
          "compat": {"minApi": 1, "maxApi": 1},
          "state": "ready", "timeMs": 1760000000000,
          "dataPlane": {"enabled": true, "running": true, "bind": "LOOPBACK",
                        "address": "127.0.0.1:47002", "port": 47002},
          "auth": {"methods": ["password"], "scopes": ["services.read"], "login": "/v1/auth/login"},
          "principal": {"kind": "peer", "nodeId": "0123456789abcdef"},
          "capabilities": ["services.call", "services.read", "status.read"],
          "services": {"listed": true, "path": "/v1/plugins", "requires": "services.read", "items": [
            {"id": "agent", "title": "Agent", "description": "Node agent",
             "capabilities": ["kv.get", "kv.put", "ping", "status"],
             "scopedOps": ["kv.get", "kv.put", "ping"]}
          ]},
          "endpoints": {"health": "/v1/health"}
        }
    """.trimIndent()

    private val statusDocument = """{"ok":true,"service":"grom-super","api":1,"fleet":{"nodeId":"0123456789abcdef"}}"""

    private val servicesDocument = """
        {"ok":true,"plugins":[
          {"id":"agent","title":"Agent","description":"Node agent",
           "capabilities":["kv.get","kv.put","ping","status"],
           "scopedOps":["kv.get","kv.put","ping"]}
        ]}
    """.trimIndent()

    private val rpcDocument = """{"ok":true,"plugin":"agent","op":"kv.get","result":{"value":"world"}}"""

    private class FakeTransport(private val table: Map<String, String>) : GromTransport {
        val requests = mutableListOf<GromHttpRequest>()
        override fun execute(request: GromHttpRequest): GromHttpResponse {
            requests.add(request)
            val path = request.url.substringAfter("http://127.0.0.1:47002")
            val body = table[path] ?: return GromHttpResponse(
                404,
                """{"ok":false,"error":{"code":"NOT_FOUND","message":"no such endpoint"}}"""
            )
            return GromHttpResponse(200, body)
        }
    }

    private fun transport() = FakeTransport(
        mapOf(
            connectPath to connectDocument,
            statusPath to statusDocument,
            pluginsPath to servicesDocument,
            rpcPath to rpcDocument
        )
    )

    @Test
    fun `the five-step flow reads discovery, services, status and one call`() {
        val transport = transport()
        val client = GromConnectClient("http://127.0.0.1:47002", transport)
        val summary = ConnectExample.run(client, GromCredential.Session("token"))

        assertEquals("0.16.0-server29", summary.gromVersion)
        assertEquals(1, summary.apiVersion)
        assertEquals("grom-connect-v1", summary.schema)
        assertEquals("peer", summary.principal)
        assertEquals(listOf("services.call", "services.read", "status.read"), summary.capabilities)
        assertEquals(listOf("agent"), summary.services.map { it.id })
        assertEquals(listOf("kv.get", "kv.put", "ping"), summary.services[0].scopedOps)
        assertEquals("grom-super", summary.status["service"])
        assertEquals("world", summary.rpcResult["value"])

        // One discovery, one service list, one status and exactly one op that
        // the service published — never `status`, never an op we guessed.
        assertEquals(
            listOf(connectPath, pluginsPath, statusPath, rpcPath),
            transport.requests.map { it.url.substringAfter("http://127.0.0.1:47002") }
        )
        assertEquals("""{"op":"kv.get","args":{"key":"example"}}""", transport.requests.last().body)
    }

    @Test
    fun `a credential without services read lists nothing and calls nothing`() {
        val transport = FakeTransport(
            mapOf(
                connectPath to """
                    {"ok":true,"schema":"grom-connect-v1","service":"grom-super","grom":"0.16.0",
                     "api":1,"compat":{"minApi":1,"maxApi":1},"principal":{"kind":"peer"},
                     "capabilities":[],"services":{"listed":false,"items":[]},
                     "auth":{"methods":["password"]},"dataPlane":{}}
                """.trimIndent()
            )
        )
        val client = GromConnectClient("http://127.0.0.1:47002", transport)
        val summary = ConnectExample.run(client, GromCredential.Session("token"))
        assertTrue(summary.services.isEmpty())
        assertTrue(summary.status.isEmpty())
        assertTrue(summary.rpcResult.isEmpty())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a password login becomes the session credential`() {
        val transport = FakeTransport(
            mapOf(
                "/v1/auth/login" to """{"ok":true,"token":"tok","expiresAtMs":1}"""
            )
        )
        val client = GromConnectClient("http://127.0.0.1:47002", transport)
        val credential = ConnectExample.loginWithPassword(client, "correct horse")
        assertEquals(GromCredential.Session("tok"), credential)
        assertEquals(
            """{"password":"correct horse"}""",
            transport.requests.single().body
        )
    }
}
