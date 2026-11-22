// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The only fleet projection that may leave the device (D59, D73). */
class FleetStatusTest {

    private val identity = NodeIdentity(
        nodeId = "0123456789abcdef",
        publicKey = "public-key-material",
        label = "Pixel 7",
        createdAtMs = 1L
    )

    @Test
    fun `the document names the device and nothing else`() {
        val document = FleetStatus.document(identity)
        assertEquals("0123456789abcdef", document["nodeId"])
        assertEquals("Pixel 7", document["label"])
        assertEquals(setOf("nodeId", "label"), document.keys)
    }

    @Test
    fun `no key address scope or peer list is present`() {
        val text = FleetStatus.document(identity).toString()
        assertFalse(text.contains("public-key-material"))
        assertFalse(text.contains("http://"))
        assertFalse(text.contains("scope"))
        assertFalse(text.contains("peers"))
    }

    @Test
    fun `an absent identity yields no block at all`() {
        assertTrue(FleetStatus.document(null).isEmpty())
    }
}
