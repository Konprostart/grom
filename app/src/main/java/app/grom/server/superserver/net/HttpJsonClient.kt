// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A raw HTTP answer: the caller decides what the status and document mean. */
data class HttpJsonResponse(
    val status: Int,
    val body: String
)

/** The transport could not complete a request. Messages never include the URL. */
class TransportException(message: String) : RuntimeException(message)

/** The seam Lightning backends use so tests never open a socket. */
interface JsonTransport {

    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int
    ): HttpJsonResponse
}

/**
 * A deliberately small JSON-over-HTTP client (D3: no third-party HTTP library).
 *
 * Two properties matter for a backend that carries an API key:
 *   - only `http` and `https` URLs are opened. In practice the Lightning
 *     configuration validates `https`, but the transport refuses everything else
 *     rather than letting a `file:` URL turn into a local read;
 *   - redirects are *not* followed. A `3xx` is returned to the caller, because
 *     silently following `Location` could send `X-Api-Key` to another host.
 *
 * Failures are reported as [TransportException] with a constant message. The URL
 * and headers may contain the credential, so they must never reach an exception
 * message (which could be logged or surfaced over the API).
 */
class HttpJsonClient : JsonTransport {

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int
    ): HttpJsonResponse {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") {
            throw TransportException("unsupported scheme")
        }

        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            throw TransportException(NETWORK_FAILURE)
        } catch (_: ClassCastException) {
            throw TransportException("unsupported scheme")
        }

        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = minOf(timeoutMs, CONNECT_TIMEOUT_CAP_MS)
            connection.readTimeout = timeoutMs
            connection.requestMethod = method

            for ((name, value) in headers) {
                if (RESTRICTED_HEADERS.any { it.equals(name, ignoreCase = true) }) continue
                connection.setRequestProperty(name, value)
            }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status >= HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
            val responseBody = stream?.use { readBounded(it) } ?: ""
            return HttpJsonResponse(status, responseBody)
        } catch (_: IOException) {
            throw TransportException(NETWORK_FAILURE)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            // Check before writing so the buffer never grows past the cap.
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw TransportException("response too large")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    companion object {

        const val MAX_RESPONSE_BYTES: Int = 64 * 1024

        private const val CONNECT_TIMEOUT_CAP_MS = 5_000
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val HTTP_BAD_REQUEST = 400
        private const val NETWORK_FAILURE = "transport failure"

        /** Hop-by-hop headers the JVM owns; a caller may not smuggle them in. */
        private val RESTRICTED_HEADERS = setOf("Host", "Content-Length", "Connection")
    }
}
