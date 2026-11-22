// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.store.SecretBox
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
 * The device identity store (D70, D72).
 *
 * The tests assert the two properties that matter: the seed is only ever on
 * disk as ciphertext, and a damaged file is refused instead of silently
 * replaced (a quiet regeneration would invalidate every pairing on every other
 * device).
 */
class NodeIdentityStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext.reversed()

        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:")
                .takeIf { sealed.startsWith("sealed:") }
                ?.reversed()
    }

    private fun store(maxBytes: Int = NodeIdentityStore.MAX_FILE_BYTES) = NodeIdentityStore(
        File(temp.root, NodeIdentityStore.FILE_NAME),
        ReversingBox(),
        maxFileBytes = maxBytes
    )

    @Test
    fun `a created identity derives its node id from the key`() {
        val identity = store().loadOrCreate("Pixel 7")
        val publicKey = Base64.getDecoder().decode(identity.publicKey)
        assertEquals(Ed25519.keyId(publicKey), identity.nodeId)
        assertEquals("Pixel 7", identity.label)
        assertEquals("1234-5678-9abc-def0", NodeIdentity.formatFingerprint("123456789abcdef0"))
    }

    @Test
    fun `the seed is not on disk in clear text`() {
        val store = store()
        val identity = store.loadOrCreate("Pixel 7")
        val seed = store.seed()
        assertNotNull(seed)

        val text = File(temp.root, NodeIdentityStore.FILE_NAME).readText()
        assertFalse(text.contains(Base64.getEncoder().encodeToString(seed)))
        assertTrue(text.contains("sealed:"))
        assertEquals(identity.nodeId, store.identity()?.nodeId)
    }

    @Test
    fun `the identity survives a reload with the same box`() {
        val first = store().loadOrCreate("Pixel 7")
        val second = store().identity()
        assertNotNull(second)
        assertEquals(first.nodeId, second?.nodeId)
        assertEquals(first.publicKey, second?.publicKey)
    }

    @Test
    fun `a tampered node id is corrupt and is never regenerated`() {
        val file = File(temp.root, NodeIdentityStore.FILE_NAME)
        store().loadOrCreate("Pixel 7")
        file.writeText(file.readText().replaceFirst(Regex("\"nodeId\":\"[0-9a-f]{16}\""), "\"nodeId\":\"0000000000000000\""))

        val broken = store()
        assertNull(broken.identity())
        assertEquals("IDENTITY_CORRUPT", broken.lastError)
        val failure = runCatching { broken.loadOrCreate("Pixel 7") }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("IDENTITY_CORRUPT", (failure as ApiException).code)
        // The file is still there, untouched, for the operator to inspect.
        assertTrue(file.isFile)
    }

    @Test
    fun `a resealed seed that does not match the public key is refused`() {
        val file = File(temp.root, NodeIdentityStore.FILE_NAME)
        store().loadOrCreate("Pixel 7")
        // Keep the public key and node id, swap in a seed sealed by the box.
        val replacement = ReversingBox().seal(
            Base64.getEncoder().encodeToString(Ed25519.generatePrivateSeed())
        )
        val text = file.readText().replaceFirst(
            Regex("\"seedSealed\":\"[^\"]+\""),
            "\"seedSealed\":\"$replacement\""
        )
        file.writeText(text)

        val broken = store()
        assertNull(broken.identity())
        assertEquals("IDENTITY_CORRUPT", broken.lastError)
    }

    @Test
    fun `an over-size file is refused before it is parsed`() {
        val file = File(temp.root, NodeIdentityStore.FILE_NAME)
        file.parentFile.mkdirs()
        file.writeText("x".repeat(NodeIdentityStore.MAX_FILE_BYTES + 10))
        val store = store()
        assertNull(store.identity())
        assertEquals("IDENTITY_CORRUPT", store.lastError)
    }

    @Test
    fun `reset mints a different identity`() {
        val store = store()
        val first = store.loadOrCreate("Pixel 7")
        val second = store.reset("Pixel 7")
        assertFalse(first.nodeId == second.nodeId)
        assertNotNull(store.seed())
    }

    @Test
    fun `a label is trimmed, bounded and control-free`() {
        assertEquals("Pixel7", NodeIdentity.normalizeLabel("  Pixel\t7  "))
        assertEquals(NodeIdentity.DEFAULT_LABEL, NodeIdentity.normalizeLabel("   "))
        assertEquals(
            NodeIdentity.MAX_LABEL_CHARS,
            NodeIdentity.normalizeLabel("x".repeat(200)).length
        )
    }
}
