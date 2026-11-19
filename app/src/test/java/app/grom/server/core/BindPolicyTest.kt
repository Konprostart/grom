// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class BindPolicyTest {

    private fun address(vararg octets: Int): InetAddress =
        InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())

    @Test
    fun `loopback ipv4 is allowed`() {
        assertTrue(BindPolicy.allows(address(127, 0, 0, 1), 47001))
    }

    @Test
    fun `loopback ipv6 is allowed`() {
        assertTrue(BindPolicy.allows(InetAddress.getByName("::1"), 47001))
    }

    @Test
    fun `wildcard ipv4 is refused`() {
        assertFalse(BindPolicy.allows(address(0, 0, 0, 0), 47001))
    }

    @Test
    fun `lan address is refused`() {
        assertFalse(BindPolicy.allows(address(192, 168, 1, 20), 47001))
    }

    @Test
    fun `null address is refused`() {
        assertFalse(BindPolicy.allows(null, 47001))
    }

    @Test
    fun `port zero is refused`() {
        assertFalse(BindPolicy.allows(address(127, 0, 0, 1), 0))
    }

    @Test
    fun `port above range is refused`() {
        assertFalse(BindPolicy.allows(address(127, 0, 0, 1), 70_000))
    }

    @Test
    fun `literal allow list is exactly the two loopback forms`() {
        assertEquals(setOf("127.0.0.1", "::1"), BindPolicy.ALLOWED)
        assertTrue(BindPolicy.isAllowedLiteral("127.0.0.1"))
        assertTrue(BindPolicy.isAllowedLiteral("::1"))
        assertFalse(BindPolicy.isAllowedLiteral("0.0.0.0"))
        assertFalse(BindPolicy.isAllowedLiteral("::"))
        assertFalse(BindPolicy.isAllowedLiteral("localhost"))
        assertFalse(BindPolicy.isAllowedLiteral(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requireLoopback throws instead of degrading`() {
        BindPolicy.requireLoopback(address(0, 0, 0, 0), 47001)
    }

    @Test
    fun `loopbackAddress is 127-0-0-1`() {
        assertEquals("127.0.0.1", BindPolicy.loopbackAddress().hostAddress)
    }
}
