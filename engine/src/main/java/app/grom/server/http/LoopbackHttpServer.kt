// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

import app.grom.server.core.BindPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The loopback listener (D5, D7, S2-R001…S2-R009).
 *
 * The socket is bound to 127.0.0.1 explicitly and the policy is re-checked
 * before binding, so a configuration mistake fails loudly instead of exposing
 * the service on an interface.
 */
class LoopbackHttpServer(
    private val port: Int,
    private val router: Router,
    private val logger: (String) -> Unit = {}
) {

    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newFixedThreadPool(MAX_CONNECTIONS, WorkerFactory())

    var boundPort: Int = -1
        private set

    fun start() {
        if (running) return
        val address = BindPolicy.loopbackAddress()
        if (!BindPolicy.allows(address, port)) {
            BindPolicy.requireLoopback(address, port) // always throws for a bad bind
            return
        }

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, port), BACKLOG)
        serverSocket = socket
        boundPort = socket.localPort
        running = true

        acceptThread = Thread({ acceptLoop(socket) }, "grom-accept").apply {
            isDaemon = true
            start()
        }
        logger("listening on ${address.hostAddress}:$boundPort")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return // the listener was closed
            }
            try {
                workers.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = IDLE_TIMEOUT_MS
                socket.tcpNoDelay = true
                val requestBytes = readRequest(socket.getInputStream()) ?: return
                val response = when (val parsed = HttpParser.parse(requestBytes)) {
                    is HttpParse.Ok -> router.handle(parsed.request)
                    is HttpParse.Failure -> failure(parsed)
                }
                writeResponse(socket.getOutputStream(), response)
            } catch (_: IOException) {
                // client vanished; nothing to do
            } catch (unexpected: Throwable) {
                logger("handler error: ${unexpected.javaClass.simpleName}")
            }
        }
    }

    private fun readRequest(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(1024)
        val chunk = ByteArray(2048)
        var headerEnd = -1

        while (headerEnd < 0) {
            val read = input.read(chunk)
            if (read < 0) {
                return if (buffer.size() == 0) null else buffer.toByteArray()
            }
            buffer.write(chunk, 0, read)
            if (buffer.size() > HttpParser.MAX_HEADER_BYTES) {
                return buffer.toByteArray() // parser answers 431
            }
            headerEnd = HttpParser.indexOf(buffer.toByteArray(), CRLFCRLF)
        }

        val declared = contentLengthOf(buffer.toByteArray(), headerEnd)
        if (declared !in 1..HttpParser.MAX_BODY_BYTES) {
            return buffer.toByteArray() // 0, invalid or oversized: let the parser decide
        }

        // Some of the body may already be in `buffer`: the first read can return
        // headers and body in one TCP segment. Counting that bytes twice made the
        // reader wait for bytes the client had already sent (a hang on POST).
        val alreadyBuffered = buffer.size() - (headerEnd + CRLFCRLF.size)
        var remaining = declared - alreadyBuffered.coerceIn(0, declared)
        while (remaining > 0) {
            val read = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (read < 0) break
            buffer.write(chunk, 0, read)
            remaining -= read
        }
        return buffer.toByteArray()
    }

    private fun contentLengthOf(raw: ByteArray, headerEnd: Int): Int {
        val text = String(raw, 0, headerEnd, Charsets.ISO_8859_1)
        val header = Regex("(?i)^content-length:[ \\t]*(\\d+)[ \\t]*$", RegexOption.MULTILINE)
            .find(text) ?: return -1
        return header.groupValues[1].toIntOrNull() ?: -1
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder(160)
        head.append("HTTP/1.1 ").append(response.status).append(' ')
            .append(reason(response.status)).append("\r\n")
        head.append("Content-Type: application/json; charset=utf-8\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("Cache-Control: no-store\r\n")
        for ((name, value) in response.headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun failure(parsed: HttpParse.Failure): HttpResponse =
        HttpResponse(
            parsed.status,
            Json.encode(
                mapOf(
                    "error" to mapOf(
                        "code" to parsed.code,
                        "message" to parsed.message
                    )
                )
            ),
            mapOf("X-Grom-Api" to "1")
        )

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        505 -> "HTTP Version Not Supported"
        else -> "Status"
    }

    private class WorkerFactory : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "grom-http-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    companion object {
        const val MAX_CONNECTIONS: Int = 8
        const val BACKLOG: Int = 16
        const val IDLE_TIMEOUT_MS: Int = 5_000
        private val CRLFCRLF = byteArrayOf(13, 10, 13, 10)
    }
}
