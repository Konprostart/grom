// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.superserver.auth.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * The paired-device store (D40, D74).
 *
 * The file is the contract: atomic replacement, a size cap enforced before the
 * write, and a corrupt file that degrades to an empty list without being
 * deleted.
 */
class PeerStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val seedA = Ed25519.generatePrivateSeed()
    private val seedB = Ed25519.generatePrivateSeed()

    private fun peer(seed: ByteArray, nodeId: String? = null, address: String = "http://10.0.0.7:47002"): PeerRecord {
        val publicKey = Ed25519.derivePublicKey(seed)
        return PeerRecord(
            nodeId = nodeId ?: Ed25519.keyId(publicKey),
            publicKey = Base64.getEncoder().encodeToString(publicKey),
            label = "Pixel 4a",
            address = address,
            grantedByMe = setOf(PeerScope.STATUS_READ, PeerScope.CONFIG_READ),
            grantedByPeer = setOf(PeerScope.STATUS_READ),
            pairedAtMs = 1_000L
        )
    }

    private fun store(maxBytes: Int = PeerStore.MAX_FILE_BYTES) =
        PeerStore(File(temp.root, PeerStore.FILE_NAME), clock = { 5_000L }, maxFileBytes = maxBytes)

    @Test
    fun `a peer round-trips and is found by node id`() {
        val store = store()
        val saved = store.add(peer(seedA))
        assertEquals(saved, store.find(saved.nodeId))
        assertEquals(1, store.list().size)
        assertTrue(File(temp.root, PeerStore.FILE_NAME).isFile)
    }

    @Test
    fun `re-pairing replaces the previous grant`() {
        val store = store()
        val first = store.add(peer(seedA))
        val second = store.add(
            first.copy(grantedByMe = setOf(PeerScope.STATUS_READ), label = "Renamed")
        )
        assertEquals(1, store.list().size)
        assertEquals("Renamed", store.find(first.nodeId)?.label)
        assertEquals(setOf(PeerScope.STATUS_READ), store.find(first.nodeId)?.grantedByMe)
    }

    @Test
    fun `remove deletes and a missing id is a reported 404`() {
        val store = store()
        val saved = store.add(peer(seedA))
        store.remove(saved.nodeId)
        assertNull(store.find(saved.nodeId))
        val failure = runCatching { store.remove(saved.nodeId) }.exceptionOrNull()
        assertEquals("PEER_NOT_FOUND", (failure as ApiException).code)
    }

    @Test
    fun `the address in the file is canonical and validation refuses anything else`() {
        val store = store()
        val record = peer(seedA)
        store.add(record)
        val text = File(temp.root, PeerStore.FILE_NAME).readText()
        assertTrue(text.contains("\"address\":\"http://10.0.0.7:47002\""))
        val failure = runCatching {
            store.add(record.copy(address = "http://10.0.0.7:47002/admin"))
        }.exceptionOrNull()
        assertEquals("BAD_PEER_ADDRESS", (failure as ApiException).code)
    }

    @Test
    fun `an empty grant is refused`() {
        val store = store()
        val failure = runCatching {
            store.add(peer(seedA).copy(grantedByMe = emptySet()))
        }.exceptionOrNull()
        assertEquals("SCOPE_REQUIRED", (failure as ApiException).code)
    }

    @Test
    fun `a probe records only measured fields`() {
        val store = store()
        val saved = store.add(peer(seedA))
        val probed = store.recordProbe(saved.nodeId, true, null)
        assertNotNull(probed)
        assertEquals(true, probed?.lastProbeOk)
        assertEquals(5_000L, probed?.lastProbeMs)
        assertEquals(saved.grantedByMe, probed?.grantedByMe)
        assertEquals("alive", probed?.state(5_000L))
        assertEquals("stale", probed?.state(5_000L + PeerRecord.ALIVE_AFTER_MS + 1))
    }

    @Test
    fun `an unknown peer id is not recorded`() {
        val store = store()
        assertNull(store.recordProbe("0000000000000000", true, null))
    }

    @Test
    fun `a corrupt file reads as empty and is not deleted`() {
        val file = File(temp.root, PeerStore.FILE_NAME)
        file.parentFile.mkdirs()
        file.writeText("{not json")
        val store = store()
        assertTrue(store.list().isEmpty())
        assertNotNull(store.lastError)
        assertTrue(file.isFile)
    }

    @Test
    fun `a record that fails validation is skipped`() {
        val file = File(temp.root, PeerStore.FILE_NAME)
        file.parentFile.mkdirs()
        val good = peer(seedA)
        file.writeText(
            "{\"version\":1,\"peers\":[" +
                "{\"nodeId\":\"${good.nodeId}\",\"publicKey\":\"${good.publicKey}\"," +
                "\"label\":\"${good.label}\",\"address\":\"${good.address}\"," +
                "\"grantedByMe\":[\"status.read\"],\"grantedByPeer\":[\"status.read\"]," +
                "\"pairedAtMs\":1}," +
                "{\"nodeId\":\"0000000000000000\",\"publicKey\":\"AAAA\",\"address\":\"http://x:1\"," +
                "\"grantedByMe\":[\"status.read\"],\"grantedByPeer\":[]}]}"
        )
        val store = store()
        assertEquals(1, store.list().size)
        assertNotNull(store.lastError)
    }

    @Test
    fun `an over-size write is refused and the old file survives`() {
        // One record fits in 520 bytes; a second one cannot.
        val store = store(maxBytes = 520)
        val saved = store.add(peer(seedA))
        val refusal = runCatching { store.add(peer(seedB)) }.exceptionOrNull()
        assertEquals("PEER_STORE_FULL", (refusal as ApiException).code)

        // The refusal happened before the write, so the first record is intact.
        val reloaded = PeerStore(File(temp.root, PeerStore.FILE_NAME))
        assertEquals(saved.nodeId, reloaded.list().single().nodeId)
    }

    @Test
    fun `the state is derived and never defaults to alive`() {
        val fresh = peer(seedA)
        assertEquals("unknown", fresh.state(1_000L))
        assertEquals("unreachable", fresh.copy(lastProbeOk = false, lastProbeMs = 1_000L).state(1_000L))
        assertEquals("alive", fresh.copy(lastProbeOk = true, lastProbeMs = 1_000L).state(1_000L))
        assertFalse(fresh.state(1_000L) == "alive")
    }
}
