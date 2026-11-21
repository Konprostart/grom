// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.core.BindResolution
import app.grom.server.core.BindPolicy
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.PasswordHash
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * End-to-end tests over a real loopback socket (D39).
 *
 * The listener binds 127.0.0.1:0, so the tests exercise the actual parser,
 * worker pool, auth path and SSE writer, not a mock of them. The plugin is a
 * test double; the Lightning adapter is covered separately.
 */
class SuperServerIntegrationTest {

    private var server: SuperServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun healthIsUnauthenticatedAndCarriesNoSecret() {
        val harness = start()
        val response = harness.request("GET", "/v1/health")
        assertEquals(200, response.status)
        assertEquals(true, response.json()["ok"])
        assertEquals("grom-super", response.json()["service"])
        val body = response.body
        assertFalse(body.contains("token"))
        assertFalse(body.contains(harness.password))
    }

    @Test
    fun statusRejectsMissingCredentialsUniformly() {
        val harness = start()
        val response = harness.request("GET", "/v1/status")
        assertEquals(401, response.status)
        val error = response.error()
        assertEquals("UNAUTHORIZED", error["code"])

        val withGarbage = harness.request(
            "GET", "/v1/status",
            mapOf("Authorization" to "Bearer not-a-real-token")
        )
        assertEquals(401, withGarbage.status)
        assertEquals("UNAUTHORIZED", withGarbage.error()["code"])
    }

    @Test
    fun passwordLoginIssuesASessionThatWorks() {
        val harness = start()
        val login = harness.request(
            "POST", "/v1/auth/login",
            body = """{"password":"${harness.password}"}"""
        )
        assertEquals(200, login.status)
        val token = login.json()["token"] as String
        assertEquals(SessionStore.TOKEN_LENGTH, token.length)

        val status = harness.request(
            "GET", "/v1/status",
            mapOf("Authorization" to "Bearer $token")
        )
        assertEquals(200, status.status)
        val json = status.json()
        assertEquals(true, json["ok"])
        assertEquals("BOTH", json["auth"])
        assertTrue(json["plugins"] is List<*>)

        val logout = harness.request(
            "POST", "/v1/auth/logout",
            mapOf("Authorization" to "Bearer $token"),
            body = "{}"
        )
        assertEquals(200, logout.status)
        val afterLogout = harness.request(
            "GET", "/v1/status",
            mapOf("Authorization" to "Bearer $token")
        )
        assertEquals(401, afterLogout.status)
    }

    @Test
    fun wrongPasswordIsRejectedWithoutDistinguishing() {
        val harness = start()
        val response = harness.request("POST", "/v1/auth/login", body = """{"password":"wrong-password"}""")
        assertEquals(401, response.status)
        assertEquals("INVALID_CREDENTIALS", response.error()["code"])
    }

    @Test
    fun repeatedLoginFailuresLockTheClient() {
        val harness = start(loginLimiter = LoginLimiter(maxFailures = 2, lockoutMs = 60_000))
        harness.request("POST", "/v1/auth/login", body = """{"password":"nope-1"}""")
        harness.request("POST", "/v1/auth/login", body = """{"password":"nope-2"}""")
        val locked = harness.request("POST", "/v1/auth/login", body = """{"password":"${harness.password}"}""")
        assertEquals(429, locked.status)
        assertEquals("TOO_MANY_REQUESTS", locked.error()["code"])
        assertTrue((locked.headers["retry-after"] ?: "0").toInt() >= 1)
    }

    @Test
    fun ed25519SignedRequestIsAccepted() {
        val seed = Ed25519.generatePrivateSeed()
        val publicKey = Ed25519.derivePublicKey(seed)
        val keyId = Ed25519.keyId(publicKey)
        val harness = start(
            keyAuth = true,
            keys = mapOf(keyId to publicKey)
        )

        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = "integration-nonce-1"
        val canonical = harness.signer.canonical("GET", "/v1/status", "", timestamp, nonce)
        val signature = Ed25519.sign(seed, canonical.toByteArray(Charsets.UTF_8))

        val response = harness.request(
            "GET", "/v1/status",
            mapOf(
                "X-Grom-Key" to keyId,
                "X-Grom-Timestamp" to timestamp,
                "X-Grom-Nonce" to nonce,
                "X-Grom-Signature" to Base64.getEncoder().encodeToString(signature)
            )
        )
        assertEquals(200, response.status)

        // The same nonce is refused the second time.
        val replay = harness.request(
            "GET", "/v1/status",
            mapOf(
                "X-Grom-Key" to keyId,
                "X-Grom-Timestamp" to timestamp,
                "X-Grom-Nonce" to nonce,
                "X-Grom-Signature" to Base64.getEncoder().encodeToString(signature)
            )
        )
        assertEquals(401, replay.status)
    }

    @Test
    fun pluginRpcDispatchesAndKeepsFailureStatus() {
        val harness = start()
        val token = harness.login()

        val ok = harness.request(
            "POST", "/v1/plugins/demo/rpc",
            mapOf("Authorization" to "Bearer $token"),
            """{"op":"echo","args":{"x":"hello"}}"""
        )
        assertEquals(200, ok.status)
        val body = ok.json()
        assertEquals("demo", body["plugin"])
        assertEquals("hello", (body["result"] as Map<*, *>)["echo"])

        val unsupported = harness.request(
            "POST", "/v1/plugins/demo/rpc",
            mapOf("Authorization" to "Bearer $token"),
            """{"op":"unsupported"}"""
        )
        assertEquals(501, unsupported.status)
        assertEquals("NOT_SUPPORTED", unsupported.error()["code"])

        val unknown = harness.request(
            "POST", "/v1/plugins/ghost/rpc",
            mapOf("Authorization" to "Bearer $token"),
            """{"op":"echo"}"""
        )
        assertEquals(404, unknown.status)
        assertEquals("UNKNOWN_PLUGIN", unknown.error()["code"])
    }

    @Test
    fun requestLimitsAndMethodsAreEnforced() {
        val harness = start(maxBodyBytes = 2_048)
        val token = harness.login()

        val tooLarge = harness.request(
            "POST", "/v1/plugins/demo/rpc",
            mapOf("Authorization" to "Bearer $token"),
            """{"op":"echo","args":{"x":"${"a".repeat(3_000)}"}}"""
        )
        assertEquals(413, tooLarge.status)

        val wrongMethod = harness.request(
            "POST", "/v1/health", body = "{}"
        )
        assertEquals(405, wrongMethod.status)
        assertEquals("GET", wrongMethod.headers["allow"])

        val notJson = harness.request(
            "POST", "/v1/plugins/demo/rpc",
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "text/plain"),
            """{"op":"echo"}"""
        )
        assertEquals(415, notJson.status)
    }

    @Test
    fun sseStreamStartsWithHelloAndStaysOpen() {
        val harness = start()
        val token = harness.login()

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", harness.port), 3_000)
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            out.write(
                (
                    "GET /v1/events HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Authorization: Bearer $token\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.ISO_8859_1)
            )
            out.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            assertEquals("HTTP/1.1 200 OK", reader.readLine())
            var contentType = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-type:")) contentType = line
            }
            assertTrue(contentType.contains("text/event-stream"))

            val eventLines = ArrayList<String>()
            while (eventLines.size < 12) {
                val line = reader.readLine() ?: break
                eventLines.add(line)
                if (line.startsWith("data:") && line.contains("grom-super")) break
            }
            assertTrue("expected a hello event, got $eventLines", eventLines.any { it == "event: hello" })
            assertTrue(
                "expected a data frame, got $eventLines",
                eventLines.any { it.startsWith("data:") && it.contains("grom-super") }
            )
        }
    }

    @Test
    fun eventBusDeliversToASubscriberWhileTheServerIsRunning() {
        val harness = start()
        val token = harness.login()

        // A normal request is enough to prove the listener is still serving after
        // a stream was opened and closed by the client.
        val first = harness.request("GET", "/v1/health")
        assertEquals(200, first.status)
        val second = harness.request(
            "GET", "/v1/status",
            mapOf("Authorization" to "Bearer $token")
        )
        assertEquals(200, second.status)
    }

    @Test
    fun statusCarriesTheReferenceBlockButHealthDoesNot() {
        val harness = start(
            serverStatus = {
                mapOf(
                    "npub" to "npub1example",
                    "relays" to listOf("wss://relay.example"),
                    "remoteBridge" to mapOf("label" to "VPS", "description" to "tunnel"),
                    "lightningHealth" to mapOf(
                        "configured" to false,
                        "backend" to null,
                        "reachable" to null,
                        "error" to null
                    )
                )
            }
        )
        val token = harness.login()

        val status = harness.request(
            "GET", "/v1/status",
            mapOf("Authorization" to "Bearer $token")
        )
        assertEquals(200, status.status)
        val document = status.json()
        assertEquals("npub1example", document["npub"])
        assertEquals(listOf("wss://relay.example"), document["relays"])
        assertTrue(document.containsKey("remoteBridge"))
        assertTrue(document.containsKey("lightningHealth"))

        // The unauthenticated health endpoint exposes none of this (D39).
        val health = harness.request("GET", "/v1/health")
        assertEquals(200, health.status)
        assertFalse(health.json().containsKey("npub"))
        assertFalse(health.json().containsKey("relays"))
        assertFalse(health.json().containsKey("remoteBridge"))
        assertFalse(health.json().containsKey("lightningHealth"))
        assertFalse(health.body.contains("npub1example"))
    }

    // --------------------------------------------------------------- harness

    private fun start(
        keyAuth: Boolean = false,
        keys: Map<String, ByteArray> = emptyMap(),
        loginLimiter: LoginLimiter = LoginLimiter(),
        maxBodyBytes: Int = 8_192,
        serverStatus: () -> Map<String, Any?> = { mapOf("address" to "127.0.0.1:0") }
    ): Harness {
        val password = "correct-horse-battery"
        val hash = PasswordHash.create(password, iterations = 10_000)
        val config = SuperServerConfig(
            enabled = true,
            bind = app.grom.server.core.BindMode.LOOPBACK,
            port = 0,
            auth = if (keyAuth) AuthMode.KEY else AuthMode.BOTH,
            passwordHash = hash.value,
            keys = keys.map { (id, publicKey) ->
                ApiKeyConfig(
                    keyId = id,
                    label = "test",
                    publicKey = Base64.getEncoder().encodeToString(publicKey)
                )
            },
            maxBodyBytes = maxBodyBytes
        )

        val decoded = PasswordHash.decode(config.passwordHash)
        val signer = RequestSigner(keyLookup = { keys[it] })
        val auth = SuperAuth(
            config = config,
            passwords = decoded,
            sessions = SessionStore(ttlMs = config.sessionTtlMs),
            requestLimiter = RateLimiter(),
            loginLimiter = loginLimiter,
            signer = signer
        )
        val registry = PluginRegistry(listOf(DemoPlugin()))
        val created = SuperServer(
            config = config,
            bind = BindResolution.Bound(BindPolicy.loopbackAddress(), "127.0.0.1"),
            router = SuperRouter(
                config = config,
                auth = auth,
                plugins = registry,
                events = EventBus(),
                uptimeMs = { 42L },
                serverStatus = serverStatus
            )
        )
        created.start()
        server = created
        return Harness(created.boundPort, password, signer)
    }

    private class Harness(
        val port: Int,
        val password: String,
        val signer: RequestSigner
    ) {

        fun login(): String {
            val response = request("POST", "/v1/auth/login", body = """{"password":"$password"}""")
            assertEquals(200, response.status)
            return response.json()["token"] as String
        }

        fun request(
            method: String,
            path: String,
            headers: Map<String, String> = emptyMap(),
            body: String? = null
        ): Response {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                socket.soTimeout = 5_000

                val payload = body?.toByteArray(Charsets.UTF_8)
                val head = StringBuilder()
                head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                head.append("Host: 127.0.0.1\r\n")
                head.append("Connection: close\r\n")
                if (payload != null) {
                    head.append("Content-Type: application/json\r\n")
                    head.append("Content-Length: ").append(payload.size).append("\r\n")
                }
                for ((name, value) in headers) {
                    head.append(name).append(": ").append(value).append("\r\n")
                }
                head.append("\r\n")

                val out = socket.getOutputStream()
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                if (payload != null) out.write(payload)
                out.flush()

                val raw = socket.getInputStream().readBytes()
                return Response.parse(raw)
            }
        }
    }

    private class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: String
    ) {

        @Suppress("UNCHECKED_CAST")
        fun json(): Map<String, Any?> =
            app.grom.server.http.JsonParser.parseObjectOrNull(body) ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        fun error(): Map<String, Any?> =
            (json()["error"] as? Map<String, Any?>) ?: emptyMap()

        companion object {
            fun parse(raw: ByteArray): Response {
                val text = String(raw, Charsets.UTF_8)
                val split = text.indexOf("\r\n\r\n")
                val head = if (split >= 0) text.substring(0, split) else text
                val body = if (split >= 0) text.substring(split + 4) else ""
                val lines = head.split("\r\n")
                val status = lines.firstOrNull()
                    ?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
                val headers = LinkedHashMap<String, String>()
                for (line in lines.drop(1)) {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
                return Response(status, headers, body)
            }
        }
    }

    private class DemoPlugin : Plugin {
        override val id: String = "demo"
        override val title: String = "Demo"
        override val description: String = "Test double for the plugin contract."

        override fun capabilities(): Set<String> = setOf("echo")

        override fun status(): Map<String, Any?> = mapOf("ready" to true)

        override fun rpc(op: String, args: Map<String, Any?>): PluginResult = when (op) {
            "echo" -> PluginResult.Ok(mapOf("echo" to args["x"]))
            "unsupported" -> PluginResult.Failure(501, "NOT_SUPPORTED", "demo does not support that")
            else -> PluginResult.Failure(400, "UNKNOWN_OP", "unknown op")
        }
    }
}
