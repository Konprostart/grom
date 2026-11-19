// SPDX-License-Identifier: Apache-2.0
package app.grom.server.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AuditStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun store(maxBytes: Long = AuditStore.DEFAULT_MAX_BYTES): Pair<AuditStore, File> {
        val dir = temporary.newFolder()
        return AuditStore(dir, maxBytes) to dir
    }

    @Test
    fun `appends produce a verifiable chain`() {
        val (store, _) = store()
        store.append("api", "agent.start", "state=STOPPED")
        store.append("api", "agent.stop", "")
        store.append("api", "token.rotate", "fingerprint=deadbeef")

        val verification = store.verify()
        assertTrue(verification.ok)
        assertEquals(3, verification.entries)
        assertEquals(3L, store.headSeq())
    }

    @Test
    fun `tail returns the newest entries in order`() {
        val (store, _) = store()
        repeat(5) { index -> store.append("api", "act", "detail=$index") }
        val tail = store.tail(2)
        assertEquals(2, tail.size)
        assertEquals(4L, tail[0].seq)
        assertEquals(5L, tail[1].seq)
        assertEquals(
            "detail",
            tail[0].detail.substringBefore("=")
        )
    }

    @Test
    fun `tail of zero is empty and a large limit returns everything`() {
        val (store, _) = store()
        repeat(3) { store.append("api", "act", "") }
        assertTrue(store.tail(0).isEmpty())
        assertEquals(3, store.tail(200).size)
    }

    @Test
    fun `entries survive a reopen`() {
        val (first, dir) = store()
        first.append("api", "one", "a")
        first.append("api", "two", "b")

        val reopened = AuditStore(dir)
        assertEquals(2, reopened.verify().entries)
        assertTrue(reopened.verify().ok)
    }

    @Test
    fun `editing the file breaks verification`() {
        val (store, dir) = store()
        store.append("api", "one", "original")
        store.append("api", "two", "second")

        val log = File(dir, "audit.log")
        val tampered = log.readText().replace("original", "modified")
        log.writeText(tampered)

        val reopened = AuditStore(dir)
        assertFalse(reopened.verify().ok)
    }

    @Test
    fun `deleting a line breaks verification`() {
        val (store, dir) = store()
        repeat(3) { index -> store.append("api", "act", "d$index") }

        val log = File(dir, "audit.log")
        val lines = log.readLines().toMutableList()
        lines.removeAt(0)
        log.writeText(lines.joinToString("\n") + "\n")

        val reopened = AuditStore(dir)
        assertFalse(reopened.verify().ok)
    }

    @Test
    fun `rotation keeps the chain verifiable across files`() {
        val (store, dir) = store(maxBytes = 260)
        repeat(12) { index -> store.append("api", "act", "payload-$index") }

        assertTrue(File(dir, "audit.log.1").isFile)
        assertTrue(File(dir, "audit.log").isFile)

        val reopened = AuditStore(dir, 260)
        val verification = reopened.verify()
        assertTrue("chain must survive rotation", verification.ok)
        assertEquals(12L, reopened.headSeq())
        assertTrue(
            "at least the newest segment must be retained",
            verification.entries in 1..12
        )
        // One generation is kept (D27), so the first retained entry links to a
        // dropped segment; audit.base records that anchor.
        assertTrue(File(dir, "audit.base").isFile)
    }

    @Test
    fun `a truncated chain verifies against its recorded base`() {
        val (store, dir) = store(maxBytes = 260)
        repeat(8) { index -> store.append("api", "act", "detail-$index") }
        val base = File(dir, "audit.base").readText().trim()
        assertTrue(base.length == 64)
        assertNotEquals(app.grom.server.core.AuditChain.GENESIS, base)
        assertTrue(AuditStore(dir, 260).verify().ok)
    }

    @Test
    fun `head hash changes with every append`() {
        val (store, _) = store()
        val genesis = store.headHash()
        store.append("api", "one", "")
        val first = store.headHash()
        store.append("api", "two", "")
        val second = store.headHash()
        assertNotEquals(genesis, first)
        assertNotEquals(first, second)
    }

    @Test
    fun `tabs and newlines in a detail cannot forge an entry`() {
        val (store, dir) = store()
        store.append("api", "act", "a\tb\nc")
        val lines = File(dir, "audit.log").readLines().filter { it.isNotEmpty() }
        assertEquals(1, lines.size)
        assertTrue(AuditStore(dir).verify().ok)
        assertEquals("a\tb\nc", AuditStore(dir).tail(1).first().detail)
    }
}
