// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.lightning

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The external-node reference (D63).
 *
 * Two properties matter: an empty record is indistinguishable from no record,
 * and the public projection can never carry the endpoint — the operator's own
 * infrastructure address stays on the device.
 */
class ExternalNodeRefTest {

    @Test
    fun `an empty record is empty`() {
        assertTrue(ExternalNodeRef().isEmpty())
        assertTrue(ExternalNodeRef(label = "   ", endpointHint = " ").isEmpty())
        assertNull(ExternalNodeRef().validate())
    }

    @Test
    fun `a described node is valid`() {
        val node = ExternalNodeRef(ExternalNodeKind.LND, "My VPS node", "node.example.com:10009")
        assertFalse(node.isEmpty())
        assertNull(node.validate())
    }

    @Test
    fun `a kind is required once anything else is set`() {
        val node = ExternalNodeRef(ExternalNodeKind.NONE, "My node", "node.example.com")
        assertEquals(
            "choose a node kind (lnd, cln or nwc) or clear the reference",
            node.validate()
        )
    }

    @Test
    fun `every kind round-trips over the wire`() {
        ExternalNodeKind.entries.forEach { kind ->
            assertEquals(kind, ExternalNodeKind.fromWire(kind.wireName))
        }
        assertEquals(ExternalNodeKind.NONE, ExternalNodeKind.fromWire("lnd-grpc"))
        assertEquals(ExternalNodeKind.NONE, ExternalNodeKind.fromWire(null))
    }

    @Test
    fun `the endpoint is a host or host-port, never a URL`() {
        listOf(
            "https://node.example.com",
            "node.example.com/path",
            "user@node.example.com",
            "grpc://node:10009",
            "node example"
        ).forEach { endpoint ->
            val node = ExternalNodeRef(ExternalNodeKind.LND, "node", endpoint)
            assertTrue(
                "expected '$endpoint' to be refused",
                node.validate() != null
            )
        }
    }

    @Test
    fun `secrets pasted into a label or endpoint are refused`() {
        val nsec = "nsec1" + "q".repeat(58)
        assertTrue(
            ExternalNodeRef(ExternalNodeKind.LND, nsec, "").validate() != null
        )
        assertTrue(
            ExternalNodeRef(
                ExternalNodeKind.LND,
                "node",
                "password=secret"
            ).validate() != null
        )
        assertTrue(
            ExternalNodeRef(
                ExternalNodeKind.LND,
                "PRIVATE KEY",
                ""
            ).validate() != null
        )
    }

    @Test
    fun `oversized fields are refused`() {
        val node = ExternalNodeRef(
            ExternalNodeKind.NWC,
            "x".repeat(ExternalNodeRef.MAX_LABEL_CHARS + 1),
            ""
        )
        assertTrue(node.validate() != null)
        val longEndpoint = ExternalNodeRef(
            ExternalNodeKind.NWC,
            "node",
            "a".repeat(ExternalNodeRef.MAX_ENDPOINT_CHARS + 1)
        )
        assertTrue(longEndpoint.validate() != null)
    }

    @Test
    fun `the public projection carries the kind and label but never the endpoint`() {
        val node = ExternalNodeRef(ExternalNodeKind.CLN, "Home node", "10.0.0.5:9736")
        val public = node.toPublicJson()
        assertEquals("cln", public["kind"])
        assertEquals("Home node", public["label"])
        assertEquals(true, public["configured"])
        assertFalse(public.containsKey("endpointHint"))
        assertFalse(Json.encode(public).contains("10.0.0.5"))
    }

    @Test
    fun `json round-trips, and an empty object is dropped`() {
        val node = ExternalNodeRef(ExternalNodeKind.LND, "VPS", "node.example.com:10009")
        val reparsed = ExternalNodeRef.fromJson(
            JsonParser.parseObjectOrNull(Json.encode(node.toJson()))
        )
        assertEquals(node, reparsed)

        // Nothing to reference is the same as no record at all.
        assertNull(
            ExternalNodeRef.fromJson(JsonParser.parseObjectOrNull("""{"kind":"none"}"""))
        )
        assertNull(ExternalNodeRef.fromJson(null))
        assertNull(ExternalNodeRef.fromJson("not a map"))
    }
}
