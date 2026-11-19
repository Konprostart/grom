// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

import java.net.URLDecoder

/**
 * A bounded HTTP/1.1 request parser (D7, S2-R004…S2-R008, S2-R029).
 *
 * Pure Kotlin: it works on a byte array so the limits can be tested without a
 * socket. The server reads at most [MAX_HEADER_BYTES] of headers and
 * [MAX_BODY_BYTES] of body before handing the buffer here.
 */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val version: String,
    val body: String
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

sealed class HttpParse {
    data class Ok(val request: HttpRequest) : HttpParse()
    data class Failure(val status: Int, val code: String, val message: String) : HttpParse()
}

object HttpParser {

    const val MAX_HEADER_BYTES: Int = 8 * 1024
    const val MAX_BODY_BYTES: Int = 64 * 1024
    const val MAX_QUERY_BYTES: Int = 2 * 1024

    private val CRLFCRLF = byteArrayOf(13, 10, 13, 10)
    private val METHOD = Regex("^[A-Z]{1,16}$")

    fun parse(raw: ByteArray): HttpParse {
        val headerEnd = indexOf(raw, CRLFCRLF)
        if (headerEnd < 0) {
            return if (raw.size > MAX_HEADER_BYTES) {
                failure(431, "HEADERS_TOO_LARGE", "request headers exceed $MAX_HEADER_BYTES bytes")
            } else {
                failure(400, "BAD_REQUEST", "incomplete request")
            }
        }
        if (headerEnd + CRLFCRLF.size > MAX_HEADER_BYTES) {
            return failure(431, "HEADERS_TOO_LARGE", "request headers exceed $MAX_HEADER_BYTES bytes")
        }

        val headerText = String(raw, 0, headerEnd, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty() || lines[0].isBlank()) {
            return failure(400, "BAD_REQUEST", "missing request line")
        }

        val requestLine = lines[0].split(" ")
        if (requestLine.size != 3) {
            return failure(400, "BAD_REQUEST", "malformed request line")
        }
        val method = requestLine[0]
        val target = requestLine[1]
        val version = requestLine[2]

        if (!METHOD.matches(method)) {
            return failure(400, "BAD_REQUEST", "unsupported method token")
        }
        if (version != "HTTP/1.1") {
            return failure(505, "HTTP_VERSION_NOT_SUPPORTED", "only HTTP/1.1 is supported")
        }
        if (!target.startsWith("/")) {
            return failure(400, "BAD_REQUEST", "target must be an absolute path")
        }

        val headers = LinkedHashMap<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) continue
            if (line[0] == ' ' || line[0] == '\t') {
                return failure(400, "BAD_REQUEST", "obsolete line folding is not supported")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) return failure(400, "BAD_REQUEST", "malformed header")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name.isEmpty()) return failure(400, "BAD_REQUEST", "header name missing")
            headers[name] = value
        }

        if (headers.containsKey("transfer-encoding")) {
            return failure(400, "BAD_REQUEST", "Transfer-Encoding is not supported")
        }

        val declaredLength: Int = if (headers.containsKey("content-length")) {
            val text = headers["content-length"]!!.trim()
            val parsed = text.toIntOrNull()
                ?: return failure(400, "BAD_REQUEST", "invalid Content-Length")
            if (parsed < 0) return failure(400, "BAD_REQUEST", "invalid Content-Length")
            if (parsed > MAX_BODY_BYTES) {
                return failure(413, "PAYLOAD_TOO_LARGE", "body exceeds $MAX_BODY_BYTES bytes")
            }
            parsed
        } else {
            0
        }

        val bodyStart = headerEnd + CRLFCRLF.size
        val bodyAvailable = raw.size - bodyStart
        if (bodyAvailable < declaredLength) {
            return failure(400, "BAD_REQUEST", "body shorter than Content-Length")
        }
        val body = String(raw, bodyStart, declaredLength, Charsets.UTF_8)

        val queryIndex = target.indexOf('?')
        val path = if (queryIndex >= 0) target.substring(0, queryIndex) else target
        val queryRaw = if (queryIndex >= 0) target.substring(queryIndex + 1) else ""
        if (queryRaw.length > MAX_QUERY_BYTES) {
            return failure(414, "URI_TOO_LONG", "query string too long")
        }

        return HttpParse.Ok(
            HttpRequest(
                method = method,
                path = decodePath(path),
                query = parseQuery(queryRaw),
                headers = headers,
                version = version,
                body = body
            )
        )
    }

    fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            val decodedKey = decode(key)
            if (decodedKey.isNotEmpty()) {
                result[decodedKey] = decode(value)
            }
        }
        return result
    }

    private fun decodePath(path: String): String = decode(path)

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }

    private fun failure(status: Int, code: String, message: String): HttpParse =
        HttpParse.Failure(status, code, message)

    fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val limit = haystack.size - needle.size
        outer@ for (i in 0..limit) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
