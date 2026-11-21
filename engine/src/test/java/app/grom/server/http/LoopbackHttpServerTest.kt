// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Socket-level regression test for the control-plane listener.
 *
 * The reader used to count body bytes twice when a client packed headers and
 * body into one TCP segment, which made a POST wait for bytes that had already
 * arrived. That path is exercised here with a single write, exactly as a local
 * client does.
 */
class LoopbackHttpServerTest {

    private var server: LoopbackHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun `a POST whose body shares the segment with the headers is answered`() {
        val api = FakeApi()
        val port = freePort()
        val listener = LoopbackHttpServer(port, Router(api))
        listener.start()
        server = listener

        val body = """{"op":"ping","args":{"cursor":42}}"""
        val response = exchange(
            port,
            "POST /grom/v1/agent/exec HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Authorization: Bearer token\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" +
                body
        )

        assertTrue("expected a 200, got: $response", response.startsWith("HTTP/1.1 200"))
        assertEquals(body, api.lastArgs)
    }

    @Test
    fun `health is answered without credentials`() {
        val api = FakeApi()
        val port = freePort()
        val listener = LoopbackHttpServer(port, Router(api))
        listener.start()
        server = listener

        val response = exchange(
            port,
            "GET /grom/v1/health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("\"state\":\"READY\""))
    }

    private fun exchange(port: Int, request: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            return socket.getInputStream().readAllText()
        }
    }

    private fun InputStream.readAllText(): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4 * 1024)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private class FakeApi : GromApi {
        var lastArgs: String? = null

        override fun apiVersion(): Int = 1
        override fun authorize(candidate: String?): Boolean = candidate == "token"
        override fun health(): Map<String, Any?> = mapOf("api" to 1, "state" to "READY")
        override fun status(): Map<String, Any?> = mapOf("state" to "READY")
        override fun auditTail(limit: Int): Map<String, Any?> = mapOf("entries" to emptyList<Any?>())
        override fun auditVerify(): Map<String, Any?> = mapOf("ok" to true)
        override fun rotateToken(): String = "rotated"
        override fun startAgent(): Map<String, Any?> = mapOf("state" to "STARTING")
        override fun stopAgent(): Map<String, Any?> = mapOf("state" to "STOPPED")
        override fun restartAgent(): Map<String, Any?> = mapOf("state" to "STARTING")
        override fun resetAgent(): Map<String, Any?> = mapOf("state" to "STOPPED")
        override fun exec(op: String, argsJson: String?): HttpResponse {
            lastArgs = argsJson
            return HttpResponse(200, """{"op":"$op","ok":true}""")
        }
    }
}
