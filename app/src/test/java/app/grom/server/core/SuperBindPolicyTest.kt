// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SuperBindPolicyTest {

    private fun address(value: String): InetAddress = InetAddress.getByName(value)

    @Test
    fun loopbackResolvesToLoopback() {
        val resolved = SuperBindPolicy.resolve(BindMode.LOOPBACK, emptyList())
        assertTrue(resolved is BindResolution.Bound)
        assertTrue((resolved as BindResolution.Bound).address.isLoopbackAddress)
        assertEquals("127.0.0.1", resolved.display)
    }

    @Test
    fun lanPrefersThePrivateAddressOverLinkLocalAndPublic() {
        val candidates = listOf(
            address("127.0.0.1"),
            address("169.254.10.20"),
            address("8.8.8.8"),
            address("192.168.1.50")
        )
        val resolved = SuperBindPolicy.resolve(BindMode.LAN, candidates)
        assertTrue(resolved is BindResolution.Bound)
        assertEquals("192.168.1.50", (resolved as BindResolution.Bound).display)
    }

    @Test
    fun lanFallsBackToAnyNonLoopbackIpv4() {
        val resolved = SuperBindPolicy.resolve(BindMode.LAN, listOf(address("8.8.8.8")))
        assertTrue(resolved is BindResolution.Bound)
        assertEquals("8.8.8.8", (resolved as BindResolution.Bound).display)
    }

    @Test
    fun lanWithoutAnAddressIsRejectedNotSilentlyDowngraded() {
        val resolved = SuperBindPolicy.resolve(
            BindMode.LAN,
            listOf(address("127.0.0.1"), address("169.254.1.1"))
        )
        assertTrue(resolved is BindResolution.Rejected)
        assertEquals("NO_LAN_ADDRESS", (resolved as BindResolution.Rejected).code)
    }

    @Test
    fun publicResolvesToTheWildcardAddress() {
        val resolved = SuperBindPolicy.resolve(BindMode.PUBLIC, emptyList())
        assertTrue(resolved is BindResolution.Bound)
        val bound = resolved as BindResolution.Bound
        assertTrue(bound.address.isAnyLocalAddress)
        assertTrue(SuperBindPolicy.isWildcard(bound.address))
    }

    @Test
    fun describeNeverReportsARejectedBindAsBound() {
        assertEquals(
            "192.168.1.50:47002",
            SuperBindPolicy.describe(
                BindResolution.Bound(address("192.168.1.50"), "192.168.1.50"),
                47_002
            )
        )
        assertEquals(
            "not bound (NO_LAN_ADDRESS)",
            SuperBindPolicy.describe(BindResolution.Rejected("NO_LAN_ADDRESS", "x"), 47_002)
        )
    }
}
