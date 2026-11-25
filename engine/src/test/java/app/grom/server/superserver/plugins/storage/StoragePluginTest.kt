// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.storage

import app.grom.server.cluster.PeerScope
import app.grom.server.superserver.PluginResult
import app.grom.server.superserver.SuperAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoragePluginTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun plugin(): StoragePlugin =
        StoragePlugin(ScopedStorageStore(temp.newFile()))

    private val peerA = SuperAuth.Principal.Peer("aaaaaaaaaaaaaaaa", setOf(PeerScope.SERVICES_CALL))
    private val peerB = SuperAuth.Principal.Peer("bbbbbbbbbbbbbbbb", setOf(PeerScope.SERVICES_CALL))

    @Test
    fun exposesOnlySafeScopedOperations() {
        assertEquals(setOf("get", "put", "delete", "list"), plugin().scopedOps())
        assertEquals(plugin().scopedOps(), plugin().capabilities())
    }

    @Test
    fun putAndGetAreConfinedToTheCallersNamespace() {
        val plugin = plugin()
        plugin.rpc("put", mapOf("key" to "theme", "value" to "dark"), peerA)

        val read = plugin.rpc("get", mapOf("key" to "theme"), peerA)
        assertEquals("dark", (read as PluginResult.Ok).payload["value"])

        // Peer B sees the same key as missing, not as peer A's value.
        val foreign = plugin.rpc("get", mapOf("key" to "theme"), peerB)
        assertTrue(foreign is PluginResult.Failure)
        assertEquals(404, (foreign as PluginResult.Failure).status)
        assertEquals("NOT_FOUND", foreign.code)
    }

    @Test
    fun operatorNamespaceIsSeparateFromPeerNamespaces() {
        val plugin = plugin()
        plugin.rpc("put", mapOf("key" to "k", "value" to "operator-value"), SuperAuth.Principal.Operator)
        plugin.rpc("put", mapOf("key" to "k", "value" to "peer-value"), peerA)

        val operatorRead = plugin.rpc("get", mapOf("key" to "k"), SuperAuth.Principal.Operator)
        assertEquals("operator-value", (operatorRead as PluginResult.Ok).payload["value"])

        val peerRead = plugin.rpc("get", mapOf("key" to "k"), peerA)
        assertEquals("peer-value", (peerRead as PluginResult.Ok).payload["value"])
    }

    @Test
    fun deleteAndListOperateOnTheCallersNamespace() {
        val plugin = plugin()
        plugin.rpc("put", mapOf("key" to "a", "value" to "1"), peerA)
        plugin.rpc("put", mapOf("key" to "b", "value" to "2"), peerA)

        val list = plugin.rpc("list", emptyMap(), peerA)
        assertEquals(listOf("a", "b"), (list as PluginResult.Ok).payload["keys"])

        val deleted = plugin.rpc("delete", mapOf("key" to "a"), peerA)
        assertEquals(true, (deleted as PluginResult.Ok).payload["deleted"])

        val missing = plugin.rpc("delete", mapOf("key" to "a"), peerA)
        assertEquals(404, (missing as PluginResult.Failure).status)
    }

    @Test
    fun aNullCallerIsRefusedByTheCallerAwarePath() {
        val plugin = plugin()
        val result = plugin.rpc("list", emptyMap(), null)
        assertTrue(result is PluginResult.Failure)
        assertEquals(403, (result as PluginResult.Failure).status)
        assertEquals("SCOPE_REQUIRED", result.code)
    }

    @Test
    fun theTwoArgRpcNeverAdmitsWithoutACaller() {
        val plugin = plugin()
        val result = plugin.rpc("put", mapOf("key" to "k", "value" to "v"))
        assertTrue(result is PluginResult.Failure)
        assertEquals(403, (result as PluginResult.Failure).status)
    }

    @Test
    fun invalidArgumentsAreStructuredFailures() {
        val plugin = plugin()
        val missingKey = plugin.rpc("get", emptyMap(), peerA)
        assertEquals(400, (missingKey as PluginResult.Failure).status)
        assertEquals("INVALID_ARGS", missingKey.code)

        val badKey = plugin.rpc("put", mapOf("key" to "with space", "value" to "v"), peerA)
        assertEquals(400, (badKey as PluginResult.Failure).status)

        val unknownOp = plugin.rpc("vacuum", emptyMap(), peerA)
        assertEquals(400, (unknownOp as PluginResult.Failure).status)
        assertEquals("UNKNOWN_OP", unknownOp.code)
    }

    @Test
    fun statusIsANonSecretSummary() {
        val plugin = plugin()
        plugin.rpc("put", mapOf("key" to "k", "value" to "v"), peerA)
        val status = plugin.status()

        assertTrue(status.containsKey("namespaces"))
        assertTrue(status.containsKey("keys"))
        assertTrue(status.containsKey("bytes"))
        // The summary never leaks stored keys or values.
        val text = status.toString()
        assertTrue(!text.contains("\"k\""))
        assertTrue(!text.contains("\"v\""))
    }
}
