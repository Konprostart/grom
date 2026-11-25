// SPDX-License-Identifier: Apache-2.0
package app.grom.examples.connect

import app.grom.sdk.connect.GromConnectClient
import app.grom.sdk.connect.GromCredential
import app.grom.sdk.connect.GromService

/**
 * The reference client flow for S6 (D93).
 *
 * This is what "Connect GROM server" looks like in a host application. It uses
 * only the `:sdk` client, which speaks the existing HTTP/JSON API — there is no
 * second protocol, no WebSocket and no MCP handshake.
 *
 * The five steps map to the specification:
 *
 *   1. discovery      `connect()` (anonymous or with the credential)
 *   2. compatibility  `supports()`
 *   3. authentication `login()` or a host-owned `GromSigner`
 *   4. capability     `services()` — never assume an op is callable
 *   5. one safe call  `rpc()` on an op the service publishes in `scopedOps`
 *
 * A host should render [Summary] (or its own strings) and show any
 * `GromConnectException.code` as a message, never a stack trace.
 */
object ConnectExample {

    /** Everything a connecting screen needs, already measured. */
    data class Summary(
        val gromVersion: String,
        val apiVersion: Int,
        val schema: String,
        /** `operator` or `peer`; a connected app should see `peer`. */
        val principal: String,
        /** The scopes this credential may actually exercise. */
        val capabilities: List<String>,
        /** Services the caller may enumerate, with their callable ops. */
        val services: List<GromService>,
        /** The measured status, or an empty map when it was not granted. */
        val status: Map<String, Any?>,
        /** The result of one published RPC op, or an empty map if none ran. */
        val rpcResult: Map<String, Any?>
    )

    /**
     * Runs the whole flow with an already-authenticated credential.
     *
     * A password host obtains one with [loginWithPassword]; a host with its own
     * Ed25519 identity passes `GromCredential.Key(signer)` and never shares a
     * password with the server.
     */
    fun run(client: GromConnectClient, credential: GromCredential): Summary {
        // 1 + 2: one round trip tells us the version, the auth methods and our
        // own capabilities. Refuse to guess when the server is newer.
        val info = client.connect(credential)
        require(info.supports(GromConnectClient.API_VERSION)) {
            "this client speaks data API ${GromConnectClient.API_VERSION}, " +
                "the server accepts ${info.minApi}..${info.maxApi}"
        }

        // 3: the service list is a scope, not an assumption.
        val services = if (info.has("services.read")) client.services(credential) else emptyList()

        // 4: status is its own scope; a client without it simply has none.
        val status = if (info.has("status.read")) client.status(credential) else emptyMap()

        // 5: call one published op. `scopedOps` is the contract; an op that is
        // not there would be refused by the server, so the client checks first.
        val rpcResult = firstCallable(services)?.let { (service, op) ->
            client.rpc(credential, service.id, op, mapOf("key" to "example")).result
        } ?: emptyMap()

        return Summary(
            gromVersion = info.gromVersion,
            apiVersion = info.api,
            schema = info.schema,
            principal = info.principalKind,
            capabilities = info.capabilities,
            services = services,
            status = status,
            rpcResult = rpcResult
        )
    }

    /** Password login, kept separate so a key-based host never calls it. */
    fun loginWithPassword(client: GromConnectClient, password: String): GromCredential =
        client.session(client.login(password).token)

    /**
     * Picks one op that is both published and useful. `kv.get` is the most
     * harmless demonstration: it reads one key and writes nothing.
     */
    private fun firstCallable(services: List<GromService>): Pair<GromService, String>? {
        val service = services.firstOrNull { it.id == "agent" } ?: services.firstOrNull()
            ?: return null
        val op = when {
            service.callable("kv.get") -> "kv.get"
            service.scopedOps.isNotEmpty() -> service.scopedOps.first()
            else -> return null
        }
        return service to op
    }
}
