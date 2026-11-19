// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private class FakeApi : GromApi {
        var authorized = true
        var lastLimit = -1
        var rotateCalls = 0

        override fun apiVersion(): Int = 1
        override fun authorize(token: String?): Boolean = authorized && token != null
        override fun health(): Map<String, Any?> = mapOf("api" to 1, "state" to "READY")
        override fun status(): Map<String, Any?> = mapOf("state" to "READY", "port" to 47001)
        override fun auditTail(limit: Int): Map<String, Any?> {
            lastLimit = limit
            return mapOf("entries" to emptyList<Any?>())
        }

        override fun auditVerify(): Map<String, Any?> = mapOf("ok" to true)
        override fun rotateToken(): String {
            rotateCalls += 1
            return "rotated-token"
        }

        override fun startAgent(): Map<String, Any?> = mapOf("state" to "STARTING")
        override fun stopAgent(): Map<String, Any?> = mapOf("state" to "STOPPED")
        override fun restartAgent(): Map<String, Any?> = mapOf("state" to "STARTING")
        override fun resetAgent(): Map<String, Any?> = mapOf("state" to "STOPPED")
        override fun exec(op: String, argsJson: String?): Map<String, Any?> = mapOf("op" to op)
    }

    private fun request(
        method: String,
        path: String,
        token: String? = null,
        body: String = "",
        query: Map<String, String> = emptyMap(),
        contentType: String? = "application/json"
    ): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        if (token != null) headers["authorization"] = "Bearer $token"
        if (contentType != null) headers["content-type"] = contentType
        return HttpRequest(method, path, query, headers, "HTTP/1.1", body)
    }

    private fun router(api: FakeApi = FakeApi()): Router = Router(api)

    @Test
    fun `health is unauthenticated and carries the api header`() {
        val response = router().handle(request("GET", "/grom/v1/health"))
        assertEquals(200, response.status)
        assertTrue(response.body.contains("READY"))
        assertEquals("1", response.headers["X-Grom-Api"])
    }

    @Test
    fun `health with a wrong method is 405`() {
        val response = router().handle(request("POST", "/grom/v1/health"))
        assertEquals(405, response.status)
        assertEquals("GET", response.headers["Allow"])
    }

    @Test
    fun `status without a token is 401`() {
        val response = router().handle(request("GET", "/grom/v1/status"))
        assertEquals(401, response.status)
        assertTrue(response.body.contains("UNAUTHORIZED"))
    }

    @Test
    fun `status with a rejected token is 401`() {
        val api = FakeApi().apply { authorized = false }
        val response = router(api).handle(request("GET", "/grom/v1/status", token = "nope"))
        assertEquals(401, response.status)
    }

    @Test
    fun `status with a valid token is 200`() {
        val response = router().handle(request("GET", "/grom/v1/status", token = "good"))
        assertEquals(200, response.status)
        assertTrue(response.body.contains("47001"))
    }

    @Test
    fun `unauthenticated callers cannot enumerate paths`() {
        val api = FakeApi().apply { authorized = false }
        val known = router(api).handle(request("GET", "/grom/v1/status"))
        val unknown = router(api).handle(request("GET", "/grom/v1/nope"))
        assertEquals(known.status, unknown.status)
        assertEquals(known.body, unknown.body)
    }

    @Test
    fun `unknown path is 404 once authenticated`() {
        val response = router().handle(request("GET", "/grom/v1/nope", token = "good"))
        assertEquals(404, response.status)
        assertTrue(response.body.contains("NOT_FOUND"))
    }

    @Test
    fun `wrong method is 405 with Allow`() {
        val response = router().handle(request("GET", "/grom/v1/agent/start", token = "good"))
        assertEquals(405, response.status)
        assertEquals("POST", response.headers["Allow"])
    }

    @Test
    fun `exec without op is 400`() {
        val response = router().handle(
            request("POST", "/grom/v1/agent/exec", token = "good", body = "{}")
        )
        assertEquals(400, response.status)
    }

    @Test
    fun `exec with op is dispatched`() {
        val response = router().handle(
            request("POST", "/grom/v1/agent/exec", token = "good", body = "{\"op\":\"ping\"}")
        )
        assertEquals(200, response.status)
        assertTrue(response.body.contains("ping"))
    }

    @Test
    fun `audit limit is clamped to 1 through 200`() {
        val api = FakeApi()
        val subject = router(api)
        subject.handle(request("GET", "/grom/v1/audit", token = "good", query = mapOf("limit" to "1000")))
        assertEquals(200, api.lastLimit)
        subject.handle(request("GET", "/grom/v1/audit", token = "good", query = mapOf("limit" to "0")))
        assertEquals(1, api.lastLimit)
        subject.handle(request("GET", "/grom/v1/audit", token = "good", query = mapOf("limit" to "5")))
        assertEquals(5, api.lastLimit)
    }

    @Test
    fun `a non numeric limit is 400`() {
        val response = router().handle(
            request("GET", "/grom/v1/audit", token = "good", query = mapOf("limit" to "many"))
        )
        assertEquals(400, response.status)
    }

    @Test
    fun `non json body is 415`() {
        val response = router().handle(
            request(
                "POST", "/grom/v1/agent/start", token = "good",
                body = "op=ping", contentType = "application/x-www-form-urlencoded"
            )
        )
        assertEquals(415, response.status)
    }

    @Test
    fun `token rotation returns the new token once`() {
        val api = FakeApi()
        val response = router(api).handle(request("POST", "/grom/v1/token/rotate", token = "good"))
        assertEquals(200, response.status)
        assertTrue(response.body.contains("rotated-token"))
        assertEquals(1, api.rotateCalls)
    }

    @Test
    fun `repeated failures trigger 429`() {
        val api = FakeApi().apply { authorized = false }
        val subject = router(api)
        repeat(20) {
            assertEquals(401, subject.handle(request("GET", "/grom/v1/status", token = "bad")).status)
        }
        assertEquals(429, subject.handle(request("GET", "/grom/v1/status", token = "bad")).status)
    }

    @Test
    fun `a success clears the failure counter`() {
        val api = FakeApi().apply { authorized = false }
        val subject = router(api)
        repeat(19) { subject.handle(request("GET", "/grom/v1/status", token = "bad")) }
        api.authorized = true
        assertEquals(200, subject.handle(request("GET", "/grom/v1/status", token = "good")).status)
        api.authorized = false
        assertEquals(401, subject.handle(request("GET", "/grom/v1/status", token = "bad")).status)
    }

    @Test
    fun `trailing slash is tolerated`() {
        val response = router().handle(request("GET", "/grom/v1/status/", token = "good"))
        assertEquals(200, response.status)
    }

    @Test
    fun `error envelope has a stable code`() {
        val response = router().handle(request("GET", "/grom/v1/status"))
        assertTrue(response.body.contains("\"code\":\"UNAUTHORIZED\""))
        assertFalse(response.body.contains("Exception"))
    }
}
