// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Peer address validation (D75).
 *
 * The stored form is canonical, so the client never parses an operator string
 * twice. Everything that could point somewhere else — userinfo, a path, a
 * query, a fragment, whitespace — is refused outright.
 */
class PeerAddressTest {

    @Test
    fun `a bare host and port become an http endpoint`() {
        assertEquals("http://192.168.1.20:47002", PeerAddress.normalize(" 192.168.1.20:47002 "))
        assertEquals("http://phone.lan:47002", PeerAddress.normalize("PHONE.LAN:47002"))
    }

    @Test
    fun `https is preserved and http is accepted`() {
        assertEquals("https://vps.example.org:8443", PeerAddress.normalize("https://VPS.example.org:8443"))
        assertEquals("http://10.0.0.5:47002", PeerAddress.normalize("http://10.0.0.5:47002"))
    }

    @Test
    fun `a non-http scheme is refused`() {
        assertNull(PeerAddress.normalize("ftp://10.0.0.5:21"))
        assertNull(PeerAddress.normalize("file:///etc/passwd"))
        assertNull(PeerAddress.normalize("javascript:alert(1)"))
    }

    @Test
    fun `userinfo a path a query and a fragment are refused`() {
        assertNull(PeerAddress.normalize("http://user:pass@10.0.0.5:47002"))
        assertNull(PeerAddress.normalize("http://10.0.0.5:47002/admin"))
        assertNull(PeerAddress.normalize("http://10.0.0.5:47002?x=1"))
        assertNull(PeerAddress.normalize("http://10.0.0.5:47002#top"))
    }

    @Test
    fun `a missing or out-of-range port is refused`() {
        assertNull(PeerAddress.normalize("10.0.0.5"))
        assertNull(PeerAddress.normalize("10.0.0.5:"))
        assertNull(PeerAddress.normalize("10.0.0.5:0"))
        assertNull(PeerAddress.normalize("10.0.0.5:65536"))
        assertNull(PeerAddress.normalize("10.0.0.5:abc"))
    }

    @Test
    fun `an empty host or a dotted path is refused`() {
        assertNull(PeerAddress.normalize(":47002"))
        assertNull(PeerAddress.normalize("."))
        assertNull(PeerAddress.normalize("..:47002"))
        assertNull(PeerAddress.normalize("host..name:47002"))
        assertNull(PeerAddress.normalize(""))
        assertNull(PeerAddress.normalize(null))
    }

    @Test
    fun `an over-long address is refused`() {
        assertNull(PeerAddress.normalize("a".repeat(300) + ":47002"))
    }
}
