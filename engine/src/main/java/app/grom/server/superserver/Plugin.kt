// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

/**
 * The plugin contract of the public data plane (D39, S5-R020…S5-R026).
 *
 * A plugin answers a small, fixed vocabulary. It is deliberately *not* a
 * dynamic loader: the registry is built in code, so nothing that arrives over
 * the network can introduce a new implementation.
 *
 * Honesty rules (S1-R017, S5-R022):
 *   - an operation the backend cannot perform returns `501 NOT_SUPPORTED` with
 *     the exact capability name — never a plausible-looking empty value;
 *   - a backend that is configured but unreachable returns `503`;
 *   - a backend that was never configured returns `503 <PLUGIN>_UNCONFIGURED`;
 *   - a measured value is either present or the call fails. Grom never invents
 *     a number to fill a hole.
 */
sealed class PluginResult {

    data class Ok(val payload: Map<String, Any?>) : PluginResult()

    data class Failure(
        val status: Int,
        val code: String,
        val message: String
    ) : PluginResult()
}

interface Plugin {

    /** Stable wire identifier; used in `/v1/plugins/{id}/rpc`. */
    val id: String

    /** Human title for the operator surface. */
    val title: String

    val description: String

    /** Capabilities the plugin can currently serve, e.g. `status`. */
    fun capabilities(): Set<String>

    /** Side-effect-free snapshot for `/v1/plugins`. Contains no secrets. */
    fun status(): Map<String, Any?>

    fun rpc(op: String, args: Map<String, Any?>): PluginResult
}
