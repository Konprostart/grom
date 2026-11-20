// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {

    private var now = 1_700_000_000_000L

    private fun store(maxSessions: Int = 16, ttlMs: Long = 60_000L): SessionStore =
        SessionStore(maxSessions = maxSessions, ttlMs = ttlMs, clock = { now })

    @Test
    fun `issued token validates and reports its expiry`() {
        val store = store(ttlMs = 10_000L)
        val issued = store.issue()
        assertEquals(now + 10_000L, issued.expiresAtMs)
        assertTrue(store.validate(issued.token))
        assertEquals(1, store.activeCount())
    }

    @Test
    fun `token has the documented shape`() {
        val store = store()
        val token = store.issue().token
        assertEquals(SessionStore.TOKEN_LENGTH, token.length)
        assertEquals(43, token.length)
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(token))
    }

    @Test
    fun `issued tokens are distinct`() {
        val store = store(maxSessions = 64)
        val tokens = HashSet<String>()
        repeat(50) { tokens.add(store.issue().token) }
        assertEquals(50, tokens.size)
    }

    @Test
    fun `wrong token is rejected`() {
        val store = store()
        val issued = store.issue()
        assertFalse(store.validate(null))
        assertFalse(store.validate(""))
        assertFalse(store.validate("not-a-token"))
        assertFalse(store.validate(issued.token + "x"))
        assertTrue(store.validate(issued.token))
    }

    @Test
    fun `revoke invalidates only the given token`() {
        val store = store()
        val first = store.issue()
        val second = store.issue()

        assertTrue(store.revoke(first.token))
        assertFalse(store.revoke(first.token))
        assertFalse(store.revoke(null))
        assertFalse(store.validate(first.token))
        assertTrue(store.validate(second.token))
        assertEquals(1, store.activeCount())
    }

    @Test
    fun `expiry is enforced through the injected clock`() {
        val store = store(ttlMs = 1_000L)
        val issued = store.issue()

        now += 999L
        assertTrue(store.validate(issued.token))
        assertEquals(1, store.activeCount())

        now += 1L
        assertFalse(store.validate(issued.token))
        assertEquals(0, store.activeCount())
    }

    @Test
    fun `expired sessions do not count toward the active total`() {
        val store = store(maxSessions = 4, ttlMs = 1_000L)
        store.issue()
        store.issue()
        now += 2_000L
        store.issue()
        assertEquals(1, store.activeCount())
    }

    @Test
    fun `the cap evicts the least recently used session`() {
        val store = store(maxSessions = 3)
        val first = store.issue()
        val second = store.issue()
        val third = store.issue()
        val fourth = store.issue()

        assertEquals(3, store.activeCount())
        assertFalse(store.validate(first.token))
        assertTrue(store.validate(second.token))
        assertTrue(store.validate(third.token))
        assertTrue(store.validate(fourth.token))
    }

    @Test
    fun `validating refreshes the least recently used order`() {
        val store = store(maxSessions = 2)
        val first = store.issue()
        val second = store.issue()

        // Touch the oldest entry so the other one is now the eviction target.
        assertTrue(store.validate(first.token))
        store.issue()

        assertEquals(2, store.activeCount())
        assertTrue(store.validate(first.token))
        assertFalse(store.validate(second.token))
    }

    @Test
    fun `revokeAll clears every session`() {
        val store = store()
        val first = store.issue()
        val second = store.issue()
        store.revokeAll()

        assertEquals(0, store.activeCount())
        assertFalse(store.validate(first.token))
        assertFalse(store.validate(second.token))
    }

    @Test
    fun `a token is not reusable across stores`() {
        val first = store()
        val second = store()
        assertNotEquals(first.issue().token, second.issue().token)
        assertTrue(first.validate(first.issue().token))
    }
}
