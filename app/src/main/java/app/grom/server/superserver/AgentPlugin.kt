// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import app.grom.server.agent.AgentChannel
import app.grom.server.http.ApiException
import app.grom.server.http.HttpResponse
import app.grom.server.http.Json
import app.grom.server.http.JsonParser

/**
 * Exposes the supervised Node agent through the public data plane (D45).
 *
 * The agent stays loopback-only and token-authenticated. This plugin is a thin
 * relay: the server forwards the op and returns the agent's own status and body.
 * It never synthesises `ok`, and it never widens the agent's whitelist.
 */
class AgentPlugin(
    private val health: () -> Map<String, Any?>,
    private val exec: (String, String?) -> HttpResponse
) : Plugin {

    override val id: String = "agent"
    override val title: String = "Agent"
    override val description: String =
        "Supervised Node agent: status, ping, echo and the session key/value store."

    override fun capabilities(): Set<String> = AgentChannel.OPS + "status" + "health"

    override fun status(): Map<String, Any?> = health()

    override fun rpc(op: String, args: Map<String, Any?>): PluginResult {
        if (op == "health" || op == "status") {
            return PluginResult.Ok(health())
        }
        if (op !in AgentChannel.OPS) {
            return PluginResult.Failure(400, "UNKNOWN_OP", "unknown op")
        }

        val body = Json.encode(mapOf("op" to op, "args" to args))
        val response = try {
            exec(op, body)
        } catch (failure: ApiException) {
            return PluginResult.Failure(failure.status, failure.code, failure.message ?: "")
        } catch (_: RuntimeException) {
            // The message could contain an address; keep the answer constant.
            return PluginResult.Failure(502, "AGENT_UNAVAILABLE", "the agent did not answer")
        }

        if (response.status !in 200..299) {
            val parsed = JsonParser.parseObjectOrNull(response.body)
            val error = parsed?.get("error") as? Map<*, *>
            val code = error?.get("code") as? String
                ?: parsed?.get("code") as? String
                ?: "AGENT_ERROR"
            val message = error?.get("message") as? String
                ?: parsed?.get("message") as? String
                ?: "the agent answered HTTP ${response.status}"
            return PluginResult.Failure(response.status, code, message)
        }

        val parsed = JsonParser.parseObjectOrNull(response.body)
            ?: return PluginResult.Failure(502, "AGENT_PROTOCOL_ERROR", "the agent answered non-JSON")
        return PluginResult.Ok(parsed)
    }
}
