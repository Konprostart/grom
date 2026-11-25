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

    /**
     * The operations a client holding `services.call` may invoke (D92).
     *
     * Empty means the service is operator-only, which is the safe default: a
     * plugin that forgets to declare this exposes nothing to a connected app.
     * Sensitive operations — reading the full status, changing configuration,
     * moving funds — MUST stay out of this set, because the route-level scope
     * only says "this caller may use services", not "this caller may do
     * anything a service can do".
     */
    fun scopedOps(): Set<String> = emptySet()

    /** Side-effect-free snapshot for `/v1/plugins`. Contains no secrets. */
    fun status(): Map<String, Any?>

    fun rpc(op: String, args: Map<String, Any?>): PluginResult

    /**
     * Caller-aware [rpc] (D92, extended for scoped storage).
     *
     * The router passes the authenticated principal so a plugin that needs
     * per-credential isolation (for example the scoped key/value store) can
     * namespace its data by the caller instead of trusting the request body.
     *
     * The default keeps every existing plugin unchanged: it delegates to the
     * two-argument [rpc] and ignores the caller, exactly as before this
     * overload existed. Plugins that must not be callable without a caller
     * override this and refuse a `null` principal.
     */
    fun rpc(op: String, args: Map<String, Any?>, caller: SuperAuth.Principal?): PluginResult =
        rpc(op, args)
}
