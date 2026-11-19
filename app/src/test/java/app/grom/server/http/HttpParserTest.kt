// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpParserTest {

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun ok(text: String): HttpRequest {
        val parsed = HttpParser.parse(bytes(text))
        assertTrue("expected a parsed request, got $parsed", parsed is HttpParse.Ok)
        return (parsed as HttpParse.Ok).request
    }

    private fun failure(text: String): HttpParse.Failure {
        val parsed = HttpParser.parse(bytes(text))
        assertTrue("expected a failure, got $parsed", parsed is HttpParse.Failure)
        return parsed as HttpParse.Failure
    }

    @Test
    fun `simple GET is parsed`() {
        val request = ok("GET /grom/v1/health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertEquals("GET", request.method)
        assertEquals("/grom/v1/health", request.path)
        assertEquals("127.0.0.1", request.header("host"))
        assertEquals("", request.body)
    }

    @Test
    fun `POST body is read exactly as declared`() {
        val body = "{\"op\":\"ping\"}"
        val request = ok(
            "POST /grom/v1/agent/exec HTTP/1.1\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body"
        )
        assertEquals(body, request.body)
    }

    @Test
    fun `query string is decoded`() {
        val request = ok("GET /grom/v1/audit?limit=5&x=a%20b HTTP/1.1\r\n\r\n")
        assertEquals("5", request.query["limit"])
        assertEquals("a b", request.query["x"])
    }

    @Test
    fun `http 1_0 is refused with 505`() {
        assertEquals(505, failure("GET /x HTTP/1.0\r\n\r\n").status)
    }

    @Test
    fun `incomplete request is 400`() {
        assertEquals(400, failure("GET /x HTTP/1.1\r\nHost: a").status)
    }

    @Test
    fun `oversized headers are 431`() {
        val padding = "X-Pad: " + "a".repeat(HttpParser.MAX_HEADER_BYTES) + "\r\n"
        assertEquals(431, failure("GET /x HTTP/1.1\r\n$padding\r\n").status)
    }

    @Test
    fun `oversized body is 413`() {
        val declared = HttpParser.MAX_BODY_BYTES + 1
        val failure = failure("POST /x HTTP/1.1\r\nContent-Length: $declared\r\n\r\n")
        assertEquals(413, failure.status)
        assertEquals("PAYLOAD_TOO_LARGE", failure.code)
    }

    @Test
    fun `transfer encoding is refused with 400`() {
        assertEquals(
            400,
            failure("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n").status
        )
    }

    @Test
    fun `obsolete line folding is refused`() {
        assertEquals(
            400,
            failure("GET /x HTTP/1.1\r\nHost: a\r\n continued: b\r\n\r\n").status
        )
    }

    @Test
    fun `malformed request line is refused`() {
        assertEquals(400, failure("GET\r\n\r\n").status)
        assertEquals(400, failure("get /x HTTP/1.1\r\n\r\n").status)
        assertEquals(400, failure("GET x HTTP/1.1\r\n\r\n").status)
    }

    @Test
    fun `short body is refused`() {
        assertEquals(
            400,
            failure("POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc").status
        )
    }

    @Test
    fun `invalid content length is refused`() {
        assertEquals(
            400,
            failure("POST /x HTTP/1.1\r\nContent-Length: abc\r\n\r\n").status
        )
    }

    @Test
    fun `header names are case insensitive`() {
        val request = ok("GET /x HTTP/1.1\r\nAuthorization: Bearer abc\r\n\r\n")
        assertEquals("Bearer abc", request.header("AUTHORIZATION"))
    }

    @Test
    fun `indexOf finds a needle`() {
        val haystack = "abc\r\n\r\ndef".toByteArray()
        val index = HttpParser.indexOf(haystack, byteArrayOf(13, 10, 13, 10))
        assertEquals(3, index)
    }
}
