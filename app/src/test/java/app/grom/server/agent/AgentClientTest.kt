// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

class AgentClientTest {

    private class Captured(val headers: Map<String, String>, val body: String)

    /**
     * Starts a one-shot loopback server, runs [block] with its port, and
     * returns the request it received.
     */
    private fun withServer(
        status: Int = 200,
        responseBody: String = "{\"ok\":true}",
        block: (Int) -> Unit
    ): Captured {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val captured = AtomicReference<Captured>()
        val thread = Thread {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val headers = LinkedHashMap<String, String>()
                reader.readLine() // request line: POST /exec HTTP/1.1
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val next = reader.read(body, read, length - read)
                    if (next < 0) break
                    read += next
                }
                captured.set(Captured(headers, String(body, 0, read)))

                val payload = responseBody.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 $status X\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.UTF_8))
                    write(payload)
                    flush()
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            block(server.localPort)
            thread.join(3_000)
        } finally {
            server.close()
        }
        return captured.get()
    }

    @Test
    fun `sends the bearer token and the request body, and returns the answer`() {
        var result: AgentClient.Result? = null
        val captured = withServer(
            status = 200,
            responseBody = "{\"op\":\"ping\",\"ok\":true}"
        ) { port ->
            result = AgentClient().exec(port, "secret-token", "{\"op\":\"ping\"}")
        }

        assertEquals(AgentClient.Result.Answered(200, "{\"op\":\"ping\",\"ok\":true}"), result)
        assertEquals("Bearer secret-token", captured.headers["authorization"])
        assertEquals("{\"op\":\"ping\"}", captured.body)
        assertTrue(captured.headers["content-type"]!!.startsWith("application/json"))
    }

    @Test
    fun `passes a non-200 agent answer through unchanged`() {
        var result: AgentClient.Result? = null
        withServer(status = 503, responseBody = "{\"ok\":false,\"code\":\"STORAGE_UNAVAILABLE\"}") { port ->
            result = AgentClient().exec(port, "t", "{\"op\":\"kv.get\"}")
        }
        assertEquals(
            AgentClient.Result.Answered(503, "{\"ok\":false,\"code\":\"STORAGE_UNAVAILABLE\"}"),
            result
        )
    }

    @Test
    fun `an unreachable agent is Unavailable, never a fabricated success`() {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = closed.localPort
        closed.close()

        val result = AgentClient(connectTimeoutMs = 300, readTimeoutMs = 300)
            .exec(port, "t", "{\"op\":\"ping\"}")
        assertTrue(result is AgentClient.Result.Unavailable)
    }

    @Test
    fun `an unknown port is Unavailable`() {
        assertTrue(AgentClient().exec(-1, "t", "{}") is AgentClient.Result.Unavailable)
    }

    @Test
    fun `an oversized request is refused before connecting`() {
        val huge = "x".repeat(AgentClient.MAX_REQUEST_BYTES + 1)
        assertTrue(AgentClient().exec(1, "t", huge) is AgentClient.Result.Unavailable)
    }

    @Test
    fun `a malformed status line is Unavailable`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val thread = Thread {
            server.accept().use { socket ->
                socket.getOutputStream().apply {
                    write("NOT-HTTP\r\n\r\n".toByteArray())
                    flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val result = AgentClient().exec(server.localPort, "t", "{\"op\":\"ping\"}")
            assertTrue(result is AgentClient.Result.Unavailable)
            thread.join(2_000)
        } finally {
            server.close()
        }
    }
}
