// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.core.BindResolution
import app.grom.server.http.HttpParse
import app.grom.server.http.HttpParser
import app.grom.server.http.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The listener of the public data plane (D39, S5-R006…S5-R012).
 *
 * Bounded on every axis that a phone cares about:
 *   - at most [SuperServerConfig.maxConnections] sockets in flight, refused with
 *     a fast `503` rather than queued;
 *   - [WORKERS] request threads, fixed;
 *   - at most [SuperServerConfig.maxSseClients] long-lived event streams, each on
 *     its own bounded thread, so a slow client cannot starve normal requests;
 *   - 10 s idle timeout, 8 KiB header cap, 64 KiB body cap (S5-R011);
 *   - `Connection: close`, no keep-alive state to leak.
 *
 * The bind address is resolved and validated by the caller before a socket
 * exists; this class never falls back to a different address.
 */
class SuperServer(
    private val config: SuperServerConfig,
    private val bind: BindResolution.Bound,
    private val router: SuperRouter,
    private val logger: (String) -> Unit = {}
) {

    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val connections = Semaphore(config.maxConnections)
    private val streams = Semaphore(config.maxSseClients)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val controls = ConcurrentHashMap.newKeySet<Control>()

    private val workers = Executors.newFixedThreadPool(WORKERS, NamedFactory("grom-super-http"))
    private val streamers = Executors.newCachedThreadPool(NamedFactory("grom-super-sse"))

    @Volatile
    var boundPort: Int = -1
        private set

    val address: String
        get() = "${bind.display}:${if (boundPort > 0) boundPort else config.port}"

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bind.address, config.port), BACKLOG)
        serverSocket = socket
        boundPort = socket.localPort
        running = true

        acceptThread = Thread({ acceptLoop(socket) }, "grom-super-accept").apply {
            isDaemon = true
            start()
        }
        logger("listening on ${bind.display}:$boundPort (${config.bind.name})")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null

        // Unblock SSE writers before shutting the pools down.
        controls.forEach { it.cancel() }
        controls.clear()
        clients.forEach { runCatching { it.close() } }
        clients.clear()

        workers.shutdownNow()
        streamers.shutdownNow()
    }

    fun isRunning(): Boolean = running

    // ---------------------------------------------------------------- accept

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            if (!connections.tryAcquire()) {
                rejectBusy(client)
                continue
            }
            try {
                workers.execute { handle(client) }
            } catch (_: RejectedExecutionException) {
                connections.release()
                runCatching { client.close() }
            }
        }
    }

    private fun rejectBusy(client: Socket) {
        try {
            client.use { socket ->
                val body = Json.encode(
                    mapOf(
                        "ok" to false,
                        "error" to mapOf(
                            "code" to "SERVER_BUSY",
                            "message" to "connection limit reached"
                        )
                    )
                )
                writeJson(socket.getOutputStream(), SuperResponse.Json(503, body))
            }
        } catch (_: IOException) {
            // The client is gone; nothing to report.
        }
    }

    // ---------------------------------------------------------------- request

    private fun handle(client: Socket) {
        clients.add(client)
        var handedOff = false
        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            client.tcpNoDelay = true

            val requestBytes = readRequest(client.getInputStream()) ?: return
            val response = when (val parsed = HttpParser.parse(requestBytes)) {
                is HttpParse.Ok -> {
                    val remote = client.inetAddress?.hostAddress ?: "unknown"
                    router.handle(parsed.request, remote)
                }
                is HttpParse.Failure -> failure(parsed)
            }

            if (response is SuperResponse.Stream) {
                if (!streams.tryAcquire()) {
                    writeJson(
                        client.getOutputStream(),
                        SuperResponse.Json(
                            503,
                            Json.encode(
                                mapOf(
                                    "ok" to false,
                                    "error" to mapOf(
                                        "code" to "TOO_MANY_STREAMS",
                                        "message" to "too many event streams are open"
                                    )
                                )
                            )
                        )
                    )
                    return
                }

                val control = Control()
                controls.add(control)
                handedOff = true
                streamers.execute {
                    try {
                        writeStreamHead(client.getOutputStream(), response.headers)
                        response.write(client.getOutputStream(), control)
                    } catch (_: IOException) {
                        // The client closed the stream.
                    } catch (unexpected: Throwable) {
                        logger("stream error: ${unexpected.javaClass.simpleName}")
                    } finally {
                        controls.remove(control)
                        streams.release()
                        clients.remove(client)
                        connections.release()
                        runCatching { client.close() }
                    }
                }
            } else {
                writeJson(client.getOutputStream(), response as SuperResponse.Json)
            }
        } catch (_: IOException) {
            // Client vanished mid-request.
        } catch (unexpected: Throwable) {
            logger("handler error: ${unexpected.javaClass.simpleName}")
        } finally {
            if (!handedOff) {
                clients.remove(client)
                connections.release()
                runCatching { client.close() }
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
                return buffer.toByteArray() // the parser answers 431
            }
            headerEnd = HttpParser.indexOf(buffer.toByteArray(), CRLFCRLF)
        }

        val declared = contentLengthOf(buffer.toByteArray(), headerEnd)
        if (declared !in 1..HttpParser.MAX_BODY_BYTES) {
            return buffer.toByteArray()
        }

        // The first read can return headers and body in one TCP segment. Bytes
        // already buffered must not be read a second time, or a POST hangs.
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

    // ----------------------------------------------------------------- output

    private fun writeJson(output: OutputStream, response: SuperResponse.Json) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder(192)
        head.append("HTTP/1.1 ").append(response.status).append(' ')
            .append(reason(response.status)).append("\r\n")
        head.append("Content-Type: application/json; charset=utf-8\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        for ((name, value) in response.headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun writeStreamHead(output: OutputStream, headers: Map<String, String>) {
        val head = StringBuilder(192)
        head.append("HTTP/1.1 200 OK\r\n")
        if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            head.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        }
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        for ((name, value) in headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun failure(parsed: HttpParse.Failure): SuperResponse.Json =
        SuperResponse.Json(
            parsed.status,
            Json.encode(
                mapOf(
                    "ok" to false,
                    "error" to mapOf("code" to parsed.code, "message" to parsed.message)
                )
            )
        )

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        505 -> "HTTP Version Not Supported"
        else -> "Status"
    }

    private class Control : StreamControl {
        private val cancelled = AtomicBoolean(false)
        override fun isCancelled(): Boolean = cancelled.get()
        fun cancel() {
            cancelled.set(true)
        }
    }

    private class NamedFactory(private val prefix: String) : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    companion object {
        const val WORKERS: Int = 4
        const val BACKLOG: Int = 16
        const val IDLE_TIMEOUT_MS: Int = 10_000
        private val CRLFCRLF = byteArrayOf(13, 10, 13, 10)
    }
}
