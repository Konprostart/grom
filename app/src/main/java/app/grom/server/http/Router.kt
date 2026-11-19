// SPDX-License-Identifier: Apache-2.0
package app.grom.server.http

import app.grom.server.core.Token

/** A response ready to be written to the socket. */
data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

/** A handler failure that maps onto a stable error code (S2-R030). */
class ApiException(
    val status: Int,
    val code: String,
    message: String
) : RuntimeException(message)

/**
 * Everything the router needs from the running server. Implemented by
 * `ServerRuntime`; the interface is pure so the router is JVM-testable (D30).
 */
interface GromApi {
    fun apiVersion(): Int
    fun authorize(candidate: String?): Boolean
    fun health(): Map<String, Any?>
    fun status(): Map<String, Any?>
    fun auditTail(limit: Int): Map<String, Any?>
    fun auditVerify(): Map<String, Any?>
    fun rotateToken(): String
    fun startAgent(): Map<String, Any?>
    fun stopAgent(): Map<String, Any?>
    fun restartAgent(): Map<String, Any?>
    fun resetAgent(): Map<String, Any?>

    /**
     * Executes a whitelisted op on the agent. The agent's own status and body
     * are returned unchanged: the server must never manufacture an `ok` that
     * the agent did not produce (S2-R025).
     */
    fun exec(op: String, argsJson: String?): HttpResponse
}

/**
 * Routing, authentication and the error envelope (S2-R010…S2-R032).
 *
 * Order matters: health is answered first (the only unauthenticated endpoint);
 * authentication is then checked before any path lookup, so an unauthenticated
 * caller cannot enumerate routes (S2-R013).
 */
class Router(
    private val api: GromApi,
    private val limiter: FailureLimiter = FailureLimiter()
) {

    fun handle(request: HttpRequest): HttpResponse {
        val path = request.path.trimEnd('/').ifEmpty { "/" }

        if (path == HEALTH) {
            return if (request.method == "GET") {
                ok(api.health())
            } else {
                methodNotAllowed(request.method, "GET")
            }
        }

        if (limiter.isBlocked()) {
            return error(429, "TOO_MANY_REQUESTS", "too many failed authentication attempts")
        }

        val credential = Token.fromHeaders(
            request.header("authorization"),
            request.header("x-grom-token")
        )
        if (credential == null || !api.authorize(credential)) {
            limiter.noteFailure()
            return error(401, "UNAUTHORIZED", "a valid bearer token is required")
        }
        limiter.noteSuccess()

        val allowed = ROUTES[path]
            ?: return error(404, "NOT_FOUND", "no such endpoint")
        if (request.method !in allowed.methods) {
            return methodNotAllowed(request.method, *allowed.methods.toTypedArray())
        }
        if (request.method == "POST" && request.body.isNotEmpty()) {
            val contentType = request.header("content-type")
            if (contentType != null && !contentType.lowercase().startsWith("application/json")) {
                return error(415, "UNSUPPORTED_MEDIA_TYPE", "request body must be application/json")
            }
        }

        return try {
            dispatch(allowed, request)
        } catch (failure: ApiException) {
            error(failure.status, failure.code, failure.message ?: "")
        }
    }

    private fun dispatch(route: Route, request: HttpRequest): HttpResponse = when (route.action) {
        Action.STATUS -> ok(api.status())
        Action.AUDIT -> ok(api.auditTail(limitOf(request)))
        Action.AUDIT_VERIFY -> ok(api.auditVerify())
        Action.TOKEN_ROTATE -> ok(mapOf("token" to api.rotateToken()))
        Action.AGENT_START -> ok(api.startAgent())
        Action.AGENT_STOP -> ok(api.stopAgent())
        Action.AGENT_RESTART -> ok(api.restartAgent())
        Action.AGENT_RESET -> ok(api.resetAgent())
        Action.AGENT_EXEC -> {
            val response = api.exec(requireOp(request.body), request.body)
            response.copy(
                headers = MAP_LIMITS +
                    ("Content-Type" to "application/json; charset=utf-8") +
                    response.headers
            )
        }
    }

    private fun limitOf(request: HttpRequest): Int {
        val raw = request.query["limit"] ?: return 20
        val value = raw.toIntOrNull()
            ?: throw ApiException(400, "BAD_REQUEST", "limit must be an integer")
        return value.coerceIn(1, 200)
    }

    private fun requireOp(body: String): String {
        if (body.isBlank()) throw ApiException(400, "BAD_REQUEST", "body is required")
        val op = JsonField.string(body, "op")
            ?: throw ApiException(400, "BAD_REQUEST", "missing field: op")
        return op
    }

    private fun ok(payload: Map<String, Any?>): HttpResponse =
        HttpResponse(200, Json.encode(payload), MAP_LIMITS)

    private fun error(status: Int, code: String, message: String): HttpResponse =
        HttpResponse(
            status,
            Json.encode(mapOf("error" to mapOf("code" to code, "message" to message))),
            MAP_LIMITS
        )

    private fun methodNotAllowed(method: String, vararg allowed: String): HttpResponse =
        HttpResponse(
            405,
            Json.encode(
                mapOf(
                    "error" to mapOf(
                        "code" to "METHOD_NOT_ALLOWED",
                        "message" to "$method is not allowed here"
                    )
                )
            ),
            MAP_LIMITS + ("Allow" to allowed.joinToString(", "))
        )

    private enum class Action {
        STATUS,
        AUDIT,
        AUDIT_VERIFY,
        TOKEN_ROTATE,
        AGENT_START,
        AGENT_STOP,
        AGENT_RESTART,
        AGENT_RESET,
        AGENT_EXEC
    }

    private class Route(val action: Action, val methods: Set<String>)

    companion object {
        const val HEALTH = "/grom/v1/health"

        private val MAP_LIMITS = mapOf("X-Grom-Api" to "1")

        private val ROUTES: Map<String, Route> = mapOf(
            "/grom/v1/status" to Route(Action.STATUS, setOf("GET")),
            "/grom/v1/audit" to Route(Action.AUDIT, setOf("GET")),
            "/grom/v1/audit/verify" to Route(Action.AUDIT_VERIFY, setOf("GET")),
            "/grom/v1/token/rotate" to Route(Action.TOKEN_ROTATE, setOf("POST")),
            "/grom/v1/agent/start" to Route(Action.AGENT_START, setOf("POST")),
            "/grom/v1/agent/stop" to Route(Action.AGENT_STOP, setOf("POST")),
            "/grom/v1/agent/restart" to Route(Action.AGENT_RESTART, setOf("POST")),
            "/grom/v1/agent/reset" to Route(Action.AGENT_RESET, setOf("POST")),
            "/grom/v1/agent/exec" to Route(Action.AGENT_EXEC, setOf("POST"))
        )
    }
}

/** The smallest useful reader: extract a top-level string field from a JSON object. */
object JsonField {

    fun string(body: String, name: String): String? {
        val pattern = Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val match = pattern.find(body) ?: return null
        return unescape(match.groupValues[1])
    }

    private fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        if (i + 5 < value.length) {
                            val code = value.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(next); i += 2
                            }
                        } else {
                            sb.append(next); i += 2
                        }
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString()
    }
}
