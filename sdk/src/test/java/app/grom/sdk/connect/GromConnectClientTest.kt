// SPDX-License-Identifier: Apache-2.0
package app.grom.sdk.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** The client half of S6 (D93): requests, signatures, parsing and refusals. */
class GromConnectClientTest {

    private val now = 1_760_000_000L
    private val nonce = "abcdefgh12345678"

    private class FakeTransport(
        private val answer: (GromHttpRequest) -> GromHttpResponse
    ) : GromTransport {
        val requests = mutableListOf<GromHttpRequest>()
        override fun execute(request: GromHttpRequest): GromHttpResponse {
            requests.add(request)
            return answer(request)
        }
    }

    private class RecordingSigner(
        private val signature: ByteArray = ByteArray(64) { 7 }
    ) : GromSigner {
        override val keyId: String = "0123456789abcdef"
        val messages = mutableListOf<ByteArray>()
        override fun sign(message: ByteArray): ByteArray {
            messages.add(message)
            return signature
        }
    }

    private fun connectDocument(
        kind: String = "anonymous",
        capabilities: List<String> = emptyList(),
        listed: Boolean = false,
        items: List<Map<String, Any?>> = emptyList(),
        methods: List<String> = listOf("key")
    ): String = GromJson.encode(
        linkedMapOf(
            "ok" to true,
            "schema" to "grom-connect-v1",
            "service" to "grom-super",
            "grom" to "0.16.0-server29",
            "api" to 1,
            "compat" to mapOf("minApi" to 1, "maxApi" to 1),
            "state" to "ready",
            "timeMs" to 1_760_000_001_000L,
            "dataPlane" to mapOf(
                "enabled" to true,
                "running" to true,
                "bind" to "LOOPBACK",
                "address" to "127.0.0.1:47002",
                "port" to 47002
            ),
            "auth" to mapOf("methods" to methods, "scopes" to listOf("status.read"), "login" to "/v1/auth/login"),
            "principal" to if (kind == "peer") {
                mapOf("kind" to kind, "nodeId" to "0123456789abcdef")
            } else {
                mapOf("kind" to kind)
            },
            "capabilities" to capabilities,
            "services" to mapOf(
                "listed" to listed,
                "path" to "/v1/plugins",
                "requires" to "services.read",
                "items" to items
            ),
            "endpoints" to mapOf("connect" to "/grom/v1/connect", "status" to "/v1/status")
        )
    )

    private fun client(
        transport: GromTransport,
        baseUrl: String = "http://127.0.0.1:47002"
    ): GromConnectClient = GromConnectClient(
        baseUrl = baseUrl,
        transport = transport,
        clock = { now },
        nonceFactory = { nonce }
    )

    // ------------------------------------------------------------ base address

    @Test
    fun `accepts http and https and fills the default port`() {
        assertEquals(
            "http://127.0.0.1:47002",
            GromConnectClient.normalizeBaseUrl("http://127.0.0.1:47002")
        )
        assertEquals("http://127.0.0.1:80", GromConnectClient.normalizeBaseUrl("http://127.0.0.1"))
        assertEquals("https://grom.example:443", GromConnectClient.normalizeBaseUrl("https://grom.example/"))
        assertEquals(
            "https://grom.example:8443",
            GromConnectClient.normalizeBaseUrl(" HTTPS://grom.example:8443 ")
        )
    }

    @Test
    fun `refuses a base address that would change the signed path`() {
        for (bad in listOf(
            "",
            "ftp://127.0.0.1:47002",
            "http://user:pass@127.0.0.1:47002",
            "http://127.0.0.1:47002/prefix",
            "http://127.0.0.1:47002/?q=1",
            "http://127.0.0.1:47002/#frag",
            "http://127.0.0.1:0",
            "not a url"
        )) {
            try {
                GromConnectClient.normalizeBaseUrl(bad)
                fail("expected a refusal for: $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    // --------------------------------------------------------------- discovery

    @Test
    fun `anonymous connect parses the discovery document`() {
        val transport = FakeTransport { GromHttpResponse(200, connectDocument(methods = listOf("password", "key"))) }
        val info = client(transport).connect()

        assertEquals(GromConnectClient.CONNECT_PATH, transport.requests.single().url.removePrefix("http://127.0.0.1:47002"))
        assertEquals("GET", transport.requests.single().method)
        assertEquals("grom-connect-v1", info.schema)
        assertEquals("0.16.0-server29", info.gromVersion)
        assertEquals(1, info.api)
        assertTrue(info.supports())
        assertFalse(info.supports(2))
        assertEquals("anonymous", info.principalKind)
        assertEquals(listOf("password", "key"), info.authMethods)
        assertFalse(info.servicesListed)
        assertTrue(info.services.isEmpty())
        assertFalse(info.has("services.read"))
        assertEquals("127.0.0.1:47002", info.dataPlane["address"])
        assertEquals(1_760_000_001_000L, info.timeMs)
    }

    @Test
    fun `connect carries the caller's capabilities and readable services`() {
        val items = listOf(
            linkedMapOf<String, Any?>(
                "id" to "agent",
                "title" to "Agent",
                "description" to "Node agent",
                "capabilities" to listOf("kv.get", "kv.put", "ping", "status"),
                "scopedOps" to listOf("kv.get", "kv.put", "ping")
            )
        )
        val transport = FakeTransport {
            GromHttpResponse(
                200,
                connectDocument(
                    kind = "peer",
                    capabilities = listOf("services.call", "services.read"),
                    listed = true,
                    items = items
                )
            )
        }
        val info = client(transport).connect(GromCredential.Key(RecordingSigner()))
        assertEquals("peer", info.principalKind)
        assertEquals("0123456789abcdef", info.principalNodeId)
        assertEquals(listOf("services.call", "services.read"), info.capabilities)
        assertTrue(info.has("services.call"))
        assertTrue(info.servicesListed)
        assertEquals(1, info.services.size)
        assertEquals("agent", info.services[0].id)
        assertTrue(info.services[0].callable("kv.put"))
        assertFalse(info.services[0].callable("status"))
        assertNull(info.services[0].status)
    }

    // ------------------------------------------------------------ authentication

    @Test
    fun `login returns a session and the token travels as a bearer header`() {
        val transport = FakeTransport { request ->
            when (request.url.removePrefix("http://127.0.0.1:47002")) {
                "/v1/auth/login" -> GromHttpResponse(
                    200,
                    """{"ok":true,"token":"${"a".repeat(43)}","expiresAtMs":99}"""
                )
                else -> GromHttpResponse(200, """{"ok":true,"plugins":[]}""")
            }
        }
        val sdk = client(transport)
        val session = sdk.login("correct horse")
        assertEquals(99L, session.expiresAtMs)

        sdk.services(GromCredential.Session(session.token))
        val headers = transport.requests.last().headers
        assertEquals("Bearer ${session.token}", headers["Authorization"])
        assertEquals("""{"password":"correct horse"}""", transport.requests.first().body)
        assertEquals("application/json; charset=utf-8", transport.requests.first().headers["Content-Type"])
    }

    @Test
    fun `a key request signs the exact canonical string and sends four headers`() {
        val signer = RecordingSigner()
        val transport = FakeTransport { GromHttpResponse(200, connectDocument()) }
        client(transport).connect(GromCredential.Key(signer))

        val request = transport.requests.single()
        val expected = GromConnectClient.canonicalString(
            "GET",
            GromConnectClient.CONNECT_PATH,
            "",
            now.toString(),
            nonce
        )
        assertEquals(expected, String(signer.messages.single(), Charsets.UTF_8))
        assertEquals(signer.keyId, request.headers[GromConnectClient.HEADER_KEY])
        assertEquals(now.toString(), request.headers[GromConnectClient.HEADER_TIMESTAMP])
        assertEquals(nonce, request.headers[GromConnectClient.HEADER_NONCE])
        assertEquals(
            Base64.getEncoder().encodeToString(ByteArray(64) { 7 }),
            request.headers[GromConnectClient.HEADER_SIGNATURE]
        )
    }

    @Test
    fun `the default nonce is random and matches the server alphabet`() {
        val first = GromConnectClient.randomNonce()
        val second = GromConnectClient.randomNonce()
        assertTrue(Regex("[A-Za-z0-9_-]{8,128}").matches(first))
        assertTrue(first != second)
    }

    // ----------------------------------------------------------------- services

    @Test
    fun `services parses capabilities and the callable subset`() {
        val body = GromJson.encode(
            mapOf(
                "ok" to true,
                "plugins" to listOf(
                    mapOf(
                        "id" to "agent",
                        "title" to "Agent",
                        "description" to "d",
                        "capabilities" to listOf("kv.get", "status"),
                        "scopedOps" to listOf("kv.get"),
                        "status" to mapOf("state" to "READY")
                    ),
                    mapOf(
                        "id" to "lightning",
                        "title" to "Lightning",
                        "description" to "d",
                        "capabilities" to listOf("pay"),
                        "scopedOps" to emptyList<String>()
                    )
                )
            )
        )
        val transport = FakeTransport { GromHttpResponse(200, body) }
        val services = client(transport).services(GromCredential.Session("t"))
        assertEquals(listOf("agent", "lightning"), services.map { it.id })
        assertEquals(listOf("kv.get"), services[0].scopedOps)
        assertEquals("READY", services[0].status?.get("state"))
        assertFalse(services[1].callable("pay"))
    }

    @Test
    fun `rpc sends the documented body on the signed path`() {
        val signer = RecordingSigner()
        val transport = FakeTransport {
            GromHttpResponse(
                200,
                """{"ok":true,"plugin":"agent","op":"kv.put","result":{"stored":true}}"""
            )
        }
        val answer = client(transport).rpc(
            GromCredential.Key(signer),
            "agent",
            "kv.put",
            mapOf("key" to "hello", "value" to "world")
        )
        assertEquals("agent", answer.plugin)
        assertEquals("kv.put", answer.op)
        assertEquals(true, answer.result["stored"])

        val request = transport.requests.single()
        assertEquals("/v1/plugins/agent/rpc", request.url.removePrefix("http://127.0.0.1:47002"))
        assertEquals("""{"op":"kv.put","args":{"key":"hello","value":"world"}}""", request.body)
        assertEquals(
            GromConnectClient.canonicalString(
                "POST",
                "/v1/plugins/agent/rpc",
                request.body,
                now.toString(),
                nonce
            ),
            String(signer.messages.single(), Charsets.UTF_8)
        )
    }

    @Test
    fun `rpc refuses an id or op that would change the signed path`() {
        val transport = FakeTransport { GromHttpResponse(200, "{}") }
        val sdk = client(transport)
        for ((service, op) in listOf(
            "../admin" to "ping",
            "agent" to "../x",
            "Agent" to "ping",
            "agent" to ""
        )) {
            try {
                sdk.rpc(GromCredential.Session("t"), service, op)
                fail("expected a refusal for $service/$op")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertTrue(transport.requests.isEmpty())
    }

    // ------------------------------------------------------------------ errors

    @Test
    fun `a refusal keeps the server's stable code`() {
        val transport = FakeTransport {
            GromHttpResponse(
                403,
                """{"ok":false,"error":{"code":"SCOPE_REQUIRED","message":"this credential does not allow that"}}"""
            )
        }
        try {
            client(transport).connect(GromCredential.Session("t"))
            fail("expected a refusal")
        } catch (failure: GromConnectException) {
            assertEquals(403, failure.status)
            assertEquals("SCOPE_REQUIRED", failure.code)
        }
    }

    @Test
    fun `a redirect is not followed and is named`() {
        val transport = FakeTransport { GromHttpResponse(302, "") }
        try {
            client(transport).connect()
            fail("expected a refusal")
        } catch (failure: GromConnectException) {
            assertEquals("REDIRECT_NOT_FOLLOWED", failure.code)
        }
    }

    @Test
    fun `a non-json answer is a bad response, not a crash`() {
        val transport = FakeTransport { GromHttpResponse(200, "<html>") }
        try {
            client(transport).connect()
            fail("expected a refusal")
        } catch (failure: GromConnectException) {
            assertEquals("BAD_RESPONSE", failure.code)
        }
    }

    @Test
    fun `a transport that throws is reported without leaking the cause`() {
        val transport = FakeTransport { throw java.io.IOException("connection refused at 10.0.0.7") }
        try {
            client(transport).connect()
            fail("expected a refusal")
        } catch (failure: GromConnectException) {
            assertEquals("TRANSPORT_FAILED", failure.code)
            assertFalse(failure.message.orEmpty().contains("10.0.0.7"))
        }
    }

    @Test
    fun `a connect document without a schema is refused`() {
        val transport = FakeTransport { GromHttpResponse(200, """{"api":1}""") }
        try {
            client(transport).connect()
            fail("expected a refusal")
        } catch (failure: GromConnectException) {
            assertEquals("BAD_CONNECT_DOCUMENT", failure.code)
        }
    }
}
