// SPDX-License-Identifier: Apache-2.0
package app.grom.server.store

import app.grom.server.core.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecureStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `first load creates a valid token`() {
        val store = SecureStore(temporary.newFolder())
        val token = store.loadOrCreate()
        assertTrue(Token.hasValidShape(token))
    }

    @Test
    fun `second load returns the same token`() {
        val store = SecureStore(temporary.newFolder())
        val first = store.loadOrCreate()
        val second = store.loadOrCreate()
        assertEquals(first, second)
    }

    @Test
    fun `rotate changes the token`() {
        val store = SecureStore(temporary.newFolder())
        val first = store.loadOrCreate()
        val rotated = store.rotate()
        assertNotEquals(first, rotated)
        assertEquals(rotated, store.current())
    }

    @Test
    fun `token survives a new store instance`() {
        val dir = temporary.newFolder()
        val token = SecureStore(dir).loadOrCreate()
        assertEquals(token, SecureStore(dir).loadOrCreate())
    }

    @Test
    fun `a corrupt token file is replaced, not trusted`() {
        val dir = temporary.newFolder()
        File(dir, SecureStore.TOKEN_FILE).writeText("not a token\n")
        val token = SecureStore(dir).loadOrCreate()
        assertTrue(Token.hasValidShape(token))
        assertNotEquals("not a token", token)
    }

    @Test
    fun `current is null before creation`() {
        val store = SecureStore(temporary.newFolder())
        assertNull(store.current())
    }

    @Test
    fun `token file is private to the owner`() {
        val dir = temporary.newFolder()
        SecureStore(dir).loadOrCreate()
        val file = File(dir, SecureStore.TOKEN_FILE)
        assertTrue(file.isFile)
        assertTrue(file.canRead())
    }
}
