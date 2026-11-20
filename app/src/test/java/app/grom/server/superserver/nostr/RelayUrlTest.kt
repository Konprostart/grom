// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayUrlTest {

    @Test
    fun acceptsSecureAndPlaintextRelays() {
        assertEquals("wss://relay.example", RelayUrl.normalizeRelay("wss://relay.example"))
        assertEquals("ws://127.0.0.1:7777", RelayUrl.normalizeRelay("ws://127.0.0.1:7777"))
    }

    @Test
    fun lowercasesSchemeAndHostButKeepsPath() {
        assertEquals(
            "wss://relay.example/Nostr",
            RelayUrl.normalizeRelay("WSS://Relay.Example/Nostr")
        )
    }

    @Test
    fun acceptsIpv6AndIpv4() {
        assertEquals("wss://[2001:db8::1]:443", RelayUrl.normalizeRelay("wss://[2001:db8::1]:443"))
        assertEquals("wss://10.0.0.5", RelayUrl.normalizeRelay("wss://10.0.0.5"))
    }

    @Test
    fun rejectsOtherSchemes() {
        assertNull(RelayUrl.normalizeRelay("https://relay.example"))
        assertNull(RelayUrl.normalizeRelay("http://relay.example"))
        assertNull(RelayUrl.normalizeRelay("relay.example"))
        assertNull(RelayUrl.normalizeRelay(""))
        assertNull(RelayUrl.normalizeRelay(null))
    }

    @Test
    fun rejectsCredentialsQueryAndFragment() {
        assertNull(RelayUrl.normalizeRelay("wss://user:pass@relay.example"))
        assertNull(RelayUrl.normalizeRelay("wss://relay.example/?x=1"))
        assertNull(RelayUrl.normalizeRelay("wss://relay.example/#frag"))
    }

    @Test
    fun rejectsBadPortsAndHosts() {
        assertNull(RelayUrl.normalizeRelay("wss://relay.example:0"))
        assertNull(RelayUrl.normalizeRelay("wss://relay.example:70000"))
        assertNull(RelayUrl.normalizeRelay("wss://relay.example:notaport"))
        assertNull(RelayUrl.normalizeRelay("wss://:443"))
        assertNull(RelayUrl.normalizeRelay("wss://relay..example"))
    }

    @Test
    fun rejectsWhitespaceAndControlCharacters() {
        assertNull(RelayUrl.normalizeRelay("wss://relay.example/a b"))
        assertNull(RelayUrl.normalizeRelay("wss://relay.example/a\nb"))
        assertNull(RelayUrl.normalizeRelay("wss://relay.example/a\u0001b"))
        assertNull(RelayUrl.normalizeRelay("wss://relay.example/" + "a".repeat(600)))
    }

    @Test
    fun normalisesListsDeduplicatesAndEnforcesTheLimit() {
        assertEquals(
            listOf("wss://a.example", "wss://b.example"),
            RelayUrl.normalizeRelays(
                listOf("wss://a.example", "WSS://A.EXAMPLE", "wss://b.example")
            )
        )
        val tooMany = (1..9).map { "wss://relay$it.example" }
        assertNull(RelayUrl.normalizeRelays(tooMany))
        assertEquals(8, RelayUrl.normalizeRelays((1..8).map { "wss://relay$it.example" })!!.size)
    }

    @Test
    fun oneBadEntryInvalidatesTheList() {
        assertNull(RelayUrl.normalizeRelays(listOf("wss://ok.example", "https://bad.example")))
    }
}
