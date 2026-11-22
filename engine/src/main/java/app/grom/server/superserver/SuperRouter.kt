// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.cluster.PairingPort
import app.grom.server.cluster.PeerScope
import app.grom.server.core.Token
import app.grom.server.http.HttpRequest
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

sealed class SuperResponse {

    data class Json(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap()
    ) : SuperResponse()

    /**
     * A response whose body is produced incrementally (SSE). The listener writes
     * the head, then hands the socket to [write] until the callback returns or
     * [StreamControl.isCancelled] reports shutdown.
     */
    class Stream(
        val headers: Map<String, String>,
        val write: (OutputStream, StreamControl) -> Unit
    ) : SuperResponse()
}

interface StreamControl {
    fun isCancelled(): Boolean
}

/**
 * Routing for the public data plane (S5-R010…S5-R026).
 *
 * The control plane (`/grom/v1/`) is untouched: it stays loopback-only and
 * token-authenticated. This router is the separately-reachable data plane.
 *
 * Order matters: health is answered without credentials (it exposes no secret),
 * the rate limiter runs before any credential work, and authentication runs
 * before path resolution so an unauthenticated caller cannot enumerate routes.
 */
class SuperRouter(
    private val config: SuperServerConfig,
    private val auth: SuperAuth,
    private val plugins: PluginRegistry,
    private val events: EventBus,
    private val uptimeMs: () -> Long,
    private val serverStatus: () -> Map<String, Any?>,
    /** Pairing routes, live only while an operator has opened a session (D71). */
    private val pairing: PairingPort = PairingPort.DISABLED,
    /** The sanitised hand-off document served at `GET /v1/config` (D53). */
    private val configExport: () -> String = { "{}" },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun handle(request: HttpRequest, client: String): SuperResponse {
        val path = normalize(request.path)

        if (path == HEALTH) {
            if (request.method != "GET") return methodNotAllowed(request.method, "GET")
            return json(
                200,
                mapOf(
                    "ok" to true,
                    "service" to SERVICE,
                    "api" to API_VERSION,
                    "uptimeMs" to uptimeMs()
                )
            )
        }

        auth.checkRequestRate(client)?.let { return rejected(it) }

        if (path == LOGIN) {
            if (request.method != "POST") return methodNotAllowed(request.method, "POST")
            if (!isJson(request)) return unsupportedMediaType()
            if (oversized(request)) return tooLarge()

            val parsed = JsonParser.parseObjectOrNull(request.body)
                ?: return badRequest("body must be a JSON object")
            val result = auth.login(client, parsed["password"] as? String)
            result.failure?.let { return rejected(it) }
            return json(
                200,
                mapOf("ok" to true, "token" to result.token, "expiresAtMs" to result.expiresAtMs)
            )
        }

        // Pairing is the only pre-authenticated surface, and only while a
        // session is open; a closed device answers every pairing route with the
        // session-closed refusal instead of exposing a hint of a surface.
        if (path == PAIR_IDENTITY || path == PAIR_HELLO || path == PAIR_POLL) {
            return pairingRoute(path, request, client)
        }

        val authenticated = when (
            val outcome = auth.authenticate(request.method, path, request.body, request::header)
        ) {
            is SuperAuth.Outcome.Rejected -> return rejected(outcome)
            is SuperAuth.Outcome.Authenticated -> outcome
        }
        val principal = authenticated.principal

        return when {
            path == STATUS -> {
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                if (!hasScope(principal, PeerScope.STATUS_READ)) return scopeRequired()
                json(200, statusPayload())
            }

            path == CONFIG -> {
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                if (!hasScope(principal, PeerScope.CONFIG_READ)) return scopeRequired()
                SuperResponse.Json(200, configExport())
            }

            path == LOGOUT -> {
                if (principal !is SuperAuth.Principal.Operator) return scopeRequired()
                if (request.method != "POST") return methodNotAllowed(request.method, "POST")
                auth.revoke(Token.fromHeaders(request.header("authorization"), request.header("x-grom-token")))
                json(200, mapOf("ok" to true))
            }

            path == PLUGINS -> {
                if (principal !is SuperAuth.Principal.Operator) return scopeRequired()
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                json(200, mapOf("ok" to true, "plugins" to plugins.all().map(::describe)))
            }

            path == EVENTS -> {
                if (principal !is SuperAuth.Principal.Operator) return scopeRequired()
                if (request.method != "GET") return methodNotAllowed(request.method, "GET")
                eventStream()
            }

            path.startsWith("$PLUGINS/") -> {
                if (principal !is SuperAuth.Principal.Operator) return scopeRequired()
                pluginRoute(path, request)
            }

            else -> error(404, "NOT_FOUND", "no such endpoint")
        }
    }

    // ------------------------------------------------------------------ routes

    /** The three pairing routes: unsigned, but live only inside a session. */
    private fun pairingRoute(path: String, request: HttpRequest, client: String): SuperResponse {
        if (path == PAIR_IDENTITY) {
            if (request.method != "GET") return methodNotAllowed(request.method, "GET")
            val document = pairing.identityDocument()
                ?: return error(404, "PAIR_SESSION_CLOSED", "no pairing session is open")
            return json(200, document)
        }
        if (request.method != "POST") return methodNotAllowed(request.method, "POST")
        if (!isJson(request)) return unsupportedMediaType()
        if (oversized(request)) return tooLarge()
        val parsed = JsonParser.parseObjectOrNull(request.body)
            ?: return badRequest("body must be a JSON object")
        val answer = if (path == PAIR_HELLO) {
            pairing.hello(parsed, client)
        } else {
            pairing.poll(parsed)
        }
        return json(answer.status, answer.payload)
    }

    private fun pluginRoute(path: String, request: HttpRequest): SuperResponse {
        val parts = path.removePrefix("$PLUGINS/").split("/")
        if (parts.isEmpty() || parts[0].isEmpty()) return error(404, "NOT_FOUND", "no such endpoint")

        val plugin = plugins.find(parts[0])
            ?: return error(404, "UNKNOWN_PLUGIN", "no plugin with that id")

        if (parts.size == 1) {
            if (request.method != "GET") return methodNotAllowed(request.method, "GET")
            return json(200, mapOf("ok" to true) + describe(plugin))
        }

        if (parts.size != 2 || parts[1] != "rpc") {
            return error(404, "NOT_FOUND", "no such endpoint")
        }
        if (request.method != "POST") return methodNotAllowed(request.method, "POST")
        if (!isJson(request)) return unsupportedMediaType()
        if (oversized(request)) return tooLarge()

        val parsed = JsonParser.parseObjectOrNull(request.body)
            ?: return badRequest("body must be a JSON object")
        val op = parsed["op"] as? String
            ?: return badRequest("missing field: op")
        if (op.isEmpty() || op.length > 64) return badRequest("invalid field: op")

        val rawArgs = parsed["args"] ?: emptyMap<String, Any?>()
        if (rawArgs !is Map<*, *>) return badRequest("field args must be an object")
        @Suppress("UNCHECKED_CAST")
        val args = rawArgs as Map<String, Any?>

        return when (val result = plugin.rpc(op, args)) {
            is PluginResult.Ok -> json(
                200,
                mapOf(
                    "ok" to true,
                    "plugin" to plugin.id,
                    "op" to op,
                    "result" to result.payload
                )
            )

            is PluginResult.Failure -> json(
                result.status,
                mapOf(
                    "ok" to false,
                    "plugin" to plugin.id,
                    "op" to op,
                    "error" to mapOf("code" to result.code, "message" to result.message)
                )
            )
        }
    }

    private fun eventStream(): SuperResponse {
        val subscription = events.subscribe()
            ?: return error(503, "TOO_MANY_STREAMS", "too many event streams are open")

        return SuperResponse.Stream(
            headers = mapOf(
                "Content-Type" to "text/event-stream; charset=utf-8",
                "X-Accel-Buffering" to "no"
            )
        ) { output, control ->
            try {
                write(output, "retry: 5000\n\n")
                write(
                    output,
                    EventBus.frame(
                        "hello",
                        mapOf("service" to SERVICE, "api" to API_VERSION)
                    )
                )
                val deadline = clock() + MAX_STREAM_MS
                while (!control.isCancelled() && clock() < deadline) {
                    val frame = subscription.queue.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS)
                    write(output, frame ?: EventBus.HEARTBEAT)
                }
                write(output, EventBus.frame("bye", mapOf("reason" to "stream closed")))
            } catch (_: IOException) {
                // The client closed the stream; nothing to report.
            } finally {
                events.unsubscribe(subscription)
            }
        }
    }

    private fun write(output: OutputStream, text: String) {
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    // ---------------------------------------------------------------- payloads

    private fun statusPayload(): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "ok" to true,
            "service" to SERVICE,
            "api" to API_VERSION,
            "uptimeMs" to uptimeMs(),
            "auth" to config.auth.name,
            "bind" to config.bind.name,
            "plugins" to plugins.all().map(::describe)
        )
        base.putAll(serverStatus())
        return base
    }

    private fun describe(plugin: Plugin): Map<String, Any?> = mapOf(
        "id" to plugin.id,
        "title" to plugin.title,
        "description" to plugin.description,
        "capabilities" to plugin.capabilities().sorted(),
        "status" to plugin.status()
    )

    // ----------------------------------------------------------------- helpers

    private fun normalize(path: String): String =
        path.trimEnd('/').ifEmpty { "/" }

    private fun isJson(request: HttpRequest): Boolean {
        if (request.body.isEmpty()) return true
        val contentType = request.header("content-type") ?: return true
        return contentType.lowercase().startsWith("application/json")
    }

    private fun oversized(request: HttpRequest): Boolean =
        request.body.toByteArray(Charsets.UTF_8).size > config.maxBodyBytes

    private fun json(status: Int, payload: Map<String, Any?>): SuperResponse.Json =
        SuperResponse.Json(status, Json.encode(payload))

    private fun error(status: Int, code: String, message: String): SuperResponse.Json =
        json(status, mapOf("ok" to false, "error" to mapOf("code" to code, "message" to message)))

    private fun rejected(outcome: SuperAuth.Outcome.Rejected): SuperResponse.Json {
        val headers = if (outcome.retryAfterSeconds > 0) {
            mapOf("Retry-After" to outcome.retryAfterSeconds.toString())
        } else {
            emptyMap()
        }
        return SuperResponse.Json(
            outcome.status,
            Json.encode(
                mapOf("ok" to false, "error" to mapOf("code" to outcome.code, "message" to outcome.message))
            ),
            headers
        )
    }

    private fun hasScope(principal: SuperAuth.Principal, scope: PeerScope): Boolean =
        when (principal) {
            is SuperAuth.Principal.Operator -> true
            is SuperAuth.Principal.Peer -> principal.scopes.contains(scope)
        }

    private fun scopeRequired(): SuperResponse.Json =
        error(403, "SCOPE_REQUIRED", "this credential does not allow that")

    private fun badRequest(message: String): SuperResponse.Json =
        error(400, "BAD_REQUEST", message)

    private fun tooLarge(): SuperResponse.Json =
        error(413, "PAYLOAD_TOO_LARGE", "request body exceeds ${config.maxBodyBytes} bytes")

    private fun unsupportedMediaType(): SuperResponse.Json =
        error(415, "UNSUPPORTED_MEDIA_TYPE", "request body must be application/json")

    private fun methodNotAllowed(method: String, vararg allowed: String): SuperResponse.Json =
        SuperResponse.Json(
            405,
            Json.encode(
                mapOf(
                    "ok" to false,
                    "error" to mapOf(
                        "code" to "METHOD_NOT_ALLOWED",
                        "message" to "$method is not allowed here"
                    )
                )
            ),
            mapOf("Allow" to allowed.joinToString(", "))
        )

    companion object {
        const val SERVICE: String = "grom-super"
        const val API_VERSION: Int = 1

        const val HEALTH: String = "/v1/health"
        const val LOGIN: String = "/v1/auth/login"
        const val LOGOUT: String = "/v1/auth/logout"
        const val STATUS: String = "/v1/status"
        const val CONFIG: String = "/v1/config"
        const val PLUGINS: String = "/v1/plugins"
        const val EVENTS: String = "/v1/events"
        const val PAIR_IDENTITY: String = "/v1/pair/identity"
        const val PAIR_HELLO: String = "/v1/pair/hello"
        const val PAIR_POLL: String = "/v1/pair/poll"

        private const val HEARTBEAT_MS: Long = 15_000
        private const val MAX_STREAM_MS: Long = 30L * 60 * 1000
    }
}
