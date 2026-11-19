// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditChainTest {

    private fun entry(previous: AuditChain.Entry?, seq: Long = (previous?.seq ?: 0L) + 1L, detail: String = "d"): AuditChain.Entry =
        AuditChain.next(previous, ts = 1_000L + seq, actor = "api", action = "act", detail = detail)

    @Test
    fun `first entry links to genesis`() {
        val first = entry(null)
        assertEquals(AuditChain.GENESIS, first.prev)
        assertEquals(1L, first.seq)
        assertEquals(64, first.hash.length)
    }

    @Test
    fun `chain verifies`() {
        val a = entry(null)
        val b = entry(a)
        val c = entry(b)
        val result = AuditChain.verify(listOf(a, b, c))
        assertTrue(result.ok)
        assertEquals(3, result.entries)
        assertEquals(c.hash, result.head)
    }

    @Test
    fun `editing a detail breaks the chain`() {
        val a = entry(null)
        val b = entry(a)
        val tampered = b.copy(detail = "changed")
        val result = AuditChain.verify(listOf(a, tampered))
        assertFalse(result.ok)
        assertEquals(b.seq, result.brokenAt)
    }

    @Test
    fun `editing a hash breaks the chain`() {
        val a = entry(null).copy(hash = "f".repeat(64))
        val result = AuditChain.verify(listOf(a))
        assertFalse(result.ok)
    }

    @Test
    fun `deleting the first entry breaks the chain`() {
        val a = entry(null)
        val b = entry(a)
        val result = AuditChain.verify(listOf(b))
        assertFalse(result.ok)
        assertEquals(2L, result.brokenAt)
    }

    @Test
    fun `reordering breaks the chain`() {
        val a = entry(null)
        val b = entry(a)
        val c = entry(b)
        val result = AuditChain.verify(listOf(a, c, b))
        assertFalse(result.ok)
    }

    @Test
    fun `empty chain verifies with genesis head`() {
        val result = AuditChain.verify(emptyList())
        assertTrue(result.ok)
        assertEquals(0, result.entries)
        assertEquals(AuditChain.GENESIS, result.head)
    }

    @Test
    fun `hash is stable for identical input`() {
        val a = AuditChain.hash(1, 2, "x", "y", "z", AuditChain.GENESIS)
        val b = AuditChain.hash(1, 2, "x", "y", "z", AuditChain.GENESIS)
        assertEquals(a, b)
    }

    @Test
    fun `field boundaries are unambiguous`() {
        val x = AuditChain.hash(1, 2, "ab", "c", "d", AuditChain.GENESIS)
        val y = AuditChain.hash(1, 2, "a", "bc", "d", AuditChain.GENESIS)
        assertNotEquals(x, y)
    }

    @Test
    fun `detail containing the separator does not collide`() {
        val x = AuditChain.hash(1, 2, "a", "b", "c\u001fd", AuditChain.GENESIS)
        val y = AuditChain.hash(1, 2, "a", "b", "c", AuditChain.GENESIS)
        assertNotEquals(x, y)
    }
}
