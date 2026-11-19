// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks to the agent's private control endpoint over loopback (S2-R025).
 *
 * The server must not answer `{"ok":true}` for an op it never executed, so
 * `exec` is proxied to the agent and the agent's status is what the caller
 * sees. The token is sent as a header and never logged; there is no URL, no
 * DNS, and no non-loopback address (S1-R003).
 *
 * Deliberately small: no HTTP library, a bounded request, a bounded response.
 */
class AgentClient(
    private val connectTimeoutMs: Int = 1_500,
    private val readTimeoutMs: Int = 3_000
) {

    sealed class Result {
        /** The agent answered; [status] and [body] are passed through. */
        data class Answered(val status: Int, val body: String) : Result()

        /** The agent could not be reached or broke the protocol mid-answer. */
        data class Unavailable(val reason: String) : Result()
    }

    /**
     * @param requestBody the original `{"op":...,"args":...}` body, forwarded
     *                    unchanged so the agent remains the single validator
     */
    fun exec(port: Int, token: String, requestBody: String): Result {
        if (port !in 1..65535) return Result.Unavailable("agent port is not known")
        if (requestBody.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            return Result.Unavailable("request body too large")
        }

        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(LOOPBACK, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.use { exchange(it, token, requestBody) }
        } catch (failure: Exception) {
            Result.Unavailable(failure.javaClass.simpleName)
        }
    }

    private fun exchange(socket: Socket, token: String, body: String): Result {
        val payload = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("POST /exec HTTP/1.1\r\n")
            append("Host: ").append(LOOPBACK).append("\r\n")
            append("Authorization: Bearer ").append(token).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }

        val output: OutputStream = socket.getOutputStream()
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(payload)
        output.flush()

        val input = BufferedInputStream(socket.getInputStream())
        val statusLine = readLine(input) ?: return Result.Unavailable("empty response")
        val status = parseStatus(statusLine)
            ?: return Result.Unavailable("malformed status line")

        var contentLength = -1
        while (true) {
            val line = readLine(input) ?: return Result.Unavailable("truncated headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: -1
            }
        }

        val bodyBytes = readBody(input, contentLength) ?: return Result.Unavailable("truncated body")
        return Result.Answered(status, String(bodyBytes, Charsets.UTF_8))
    }

    private fun readBody(input: InputStream, contentLength: Int): ByteArray? {
        val expected = if (contentLength >= 0) contentLength else -1
        if (expected > MAX_RESPONSE_BYTES) return null
        val buffer = ByteArrayOutputStream(if (expected > 0) expected else 512)
        val chunk = ByteArray(8 * 1024)
        while (true) {
            if (expected >= 0 && buffer.size() >= expected) break
            val read = input.read(chunk)
            if (read <= 0) break
            if (buffer.size() + read > MAX_RESPONSE_BYTES) return null
            buffer.write(chunk, 0, read)
        }
        val bytes = buffer.toByteArray()
        if (expected >= 0 && bytes.size < expected) return null
        return bytes
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder(64)
        while (true) {
            val next = input.read()
            if (next < 0) return if (line.isEmpty()) null else line.toString()
            if (next == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= MAX_HEADER_BYTES) return null
            line.append(next.toChar())
        }
    }

    private fun parseStatus(line: String): Int? {
        val parts = line.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return null
        return parts[1].toIntOrNull()?.takeIf { it in 100..599 }
    }

    companion object {
        const val LOOPBACK: String = "127.0.0.1"
        const val MAX_REQUEST_BYTES: Int = 64 * 1024
        const val MAX_RESPONSE_BYTES: Int = 256 * 1024
        const val MAX_HEADER_BYTES: Int = 8 * 1024
    }
}
