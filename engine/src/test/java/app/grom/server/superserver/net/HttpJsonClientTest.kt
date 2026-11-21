// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises [HttpJsonClient] against a real socket.
 *
 * A tiny hand-written HTTP/1.1 server is used instead of
 * `com.sun.net.httpserver`, because the Android unit-test compile classpath does
 * not expose the JDK's `jdk.httpserver` module. This also keeps the fixture
 * dependency-free and in control of the exact bytes on the wire (which matters
 * for the redirect and response-cap tests).
 */
class HttpJsonClientTest {

    private lateinit var server: TestHttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = TestHttpServer()
        server.start()
        baseUrl = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `GET sends caller headers and returns the response body`() {
        val seenMethod = AtomicReference<String>()
        val seenKey = AtomicReference<String>()
        server.route("/get") { request ->
            seenMethod.set(request.method)
            seenKey.set(request.headers["x-api-key"])
            TestResponse(200, """{"ok":true}""")
        }

        val response = HttpJsonClient().request(
            "GET",
            "$baseUrl/get",
            mapOf("X-Api-Key" to "test-key"),
            null,
            2_000
        )

        assertEquals(200, response.status)
        assertEquals("""{"ok":true}""", response.body)
        assertEquals("GET", seenMethod.get())
        assertEquals("test-key", seenKey.get())
    }

    @Test
    fun `POST writes the body as JSON with the content type header`() {
        val seenBody = AtomicReference<String>()
        val seenContentType = AtomicReference<String>()
        server.route("/post") { request ->
            seenBody.set(request.body)
            seenContentType.set(request.headers["content-type"])
            TestResponse(200, """{"ok":true}""")
        }

        val response = HttpJsonClient().request(
            "POST",
            "$baseUrl/post",
            mapOf("X-Api-Key" to "test-key"),
            """{"out":false,"amount":1}""",
            2_000
        )

        assertEquals(200, response.status)
        assertEquals("""{"out":false,"amount":1}""", seenBody.get())
        assertEquals("application/json", seenContentType.get())
    }

    @Test
    fun `a non-200 body is read from the error stream instead of failing`() {
        server.route("/error") { TestResponse(400, """{"error":"bad request"}""") }

        val response = HttpJsonClient().request(
            "GET",
            "$baseUrl/error",
            emptyMap(),
            null,
            2_000
        )

        assertEquals(400, response.status)
        assertEquals("""{"error":"bad request"}""", response.body)
    }

    @Test
    fun `a redirect is returned and never followed`() {
        val targetHits = AtomicInteger()
        server.route("/target") {
            targetHits.incrementAndGet()
            TestResponse(200, """{"followed":true}""")
        }
        server.route("/redirect") {
            TestResponse(302, "", mapOf("Location" to "$baseUrl/target"))
        }

        val response = HttpJsonClient().request(
            "GET",
            "$baseUrl/redirect",
            emptyMap(),
            null,
            2_000
        )

        assertEquals(302, response.status)
        assertEquals(0, targetHits.get())
    }

    @Test
    fun `a response larger than the cap is refused`() {
        val huge = "x".repeat(100 * 1024)
        server.route("/huge") { TestResponse(200, huge) }

        try {
            HttpJsonClient().request("GET", "$baseUrl/huge", emptyMap(), null, 5_000)
            fail("expected TransportException")
        } catch (failure: TransportException) {
            assertEquals("response too large", failure.message)
        }
    }

    @Test
    fun `a non-http scheme is rejected before opening anything`() {
        try {
            HttpJsonClient().request("GET", "ftp://127.0.0.1/x", emptyMap(), null, 1_000)
            fail("expected TransportException")
        } catch (failure: TransportException) {
            assertEquals("unsupported scheme", failure.message)
        }
    }

    // ------------------------------------------------------------- fixture

    private class TestRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private class TestResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap()
    )

    private class TestHttpServer {

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val routes = ConcurrentHashMap<String, (TestRequest) -> TestResponse>()
        private val acceptThread = Thread({ acceptLoop() }, "test-http").apply { isDaemon = true }
        private val workers = ConcurrentHashMap.newKeySet<Thread>()

        @Volatile
        private var running = true

        val port: Int get() = socket.localPort

        fun route(path: String, handler: (TestRequest) -> TestResponse) {
            routes[path] = handler
        }

        fun start() {
            acceptThread.start()
        }

        fun close() {
            running = false
            runCatching { socket.close() }
            workers.forEach { it.interrupt() }
        }

        private fun acceptLoop() {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    return
                }
                val worker = Thread({ handle(client) }, "test-http-worker").apply { isDaemon = true }
                workers.add(worker)
                worker.start()
            }
        }

        private fun handle(client: Socket) {
            try {
                client.use { socket ->
                    socket.soTimeout = 5_000
                    val input = BufferedInputStream(socket.getInputStream())
                    val requestLine = readLine(input) ?: return
                    val parts = requestLine.split(" ")
                    if (parts.size < 2) return
                    val method = parts[0]
                    val path = parts[1].substringBefore('?')

                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val line = readLine(input) ?: return
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            headers[line.substring(0, colon).trim().lowercase()] =
                                line.substring(colon + 1).trim()
                        }
                    }

                    val declared = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = readBody(input, declared)

                    val handler = routes[path]
                    val response = handler?.invoke(TestRequest(method, path, headers, body))
                        ?: TestResponse(404, """{"error":"no route"}""")
                    write(socket.getOutputStream(), response)
                }
            } catch (_: IOException) {
                // The client aborted (for example after the response cap).
            } finally {
                workers.remove(Thread.currentThread())
            }
        }

        private fun write(output: OutputStream, response: TestResponse) {
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            val head = StringBuilder()
            head.append("HTTP/1.1 ").append(response.status).append(" OK\r\n")
            head.append("Content-Type: application/json\r\n")
            head.append("Content-Length: ").append(bytes.size).append("\r\n")
            head.append("Connection: close\r\n")
            for ((name, value) in response.headers) {
                head.append(name).append(": ").append(value).append("\r\n")
            }
            head.append("\r\n")
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            output.write(bytes)
            output.flush()
        }

        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream(128)
            while (true) {
                val next = input.read()
                if (next < 0) return if (buffer.size() == 0) null else String(buffer.toByteArray(), Charsets.ISO_8859_1)
                if (next == '\n'.code) return String(buffer.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r')
                if (buffer.size() > 8 * 1024) return null
                buffer.write(next)
            }
        }

        private fun readBody(input: InputStream, length: Int): String {
            if (length <= 0) return ""
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) break
                offset += read
            }
            return String(bytes, 0, offset, Charsets.UTF_8)
        }
    }
}
