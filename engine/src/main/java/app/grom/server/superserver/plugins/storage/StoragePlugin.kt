// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.storage

import app.grom.server.superserver.Plugin
import app.grom.server.superserver.PluginResult
import app.grom.server.superserver.SuperAuth

/**
 * The public `storage` plugin: a persistent, per-credential key/value store
 * exposed to connected apps through the existing data-plane RPC route.
 *
 * Isolation is the whole point (D92): the caller's namespace is derived from the
 * verified principal, never from a request body field. A scoped peer gets the
 * namespace `peer:<keyId>` and the operator the reserved `operator` namespace, so
 * Tariffia Pay can store its own settings in Grom without being able to read or
 * overwrite another app's data — and without ever seeing Grom's financial or
 * administrative secrets.
 *
 * All four operations are safe to grant to a `services.call` credential because
 * they are confined to that namespace; nothing here moves funds or reads the
 * operator surface.
 */
class StoragePlugin(
    private val store: ScopedStorageStore
) : Plugin {

    override val id: String = "storage"

    override val title: String = "Storage"

    override val description: String =
        "Persistent per-credential key/value store for connected apps."

    override fun capabilities(): Set<String> = OPS

    /**
     * Every op is scoped (D92): `get`, `put`, `delete` and `list` only ever
     * touch the caller's own namespace, so a connected app may use all of them
     * without gaining anything it did not already own.
     */
    override fun scopedOps(): Set<String> = OPS

    override fun status(): Map<String, Any?> = store.summary()

    override fun rpc(op: String, args: Map<String, Any?>): PluginResult =
        // Without a verified caller there is no namespace to confine the op to;
        // the router never reaches this path without one.
        PluginResult.Failure(403, "SCOPE_REQUIRED", "a verified credential is required")

    override fun rpc(
        op: String,
        args: Map<String, Any?>,
        caller: SuperAuth.Principal?
    ): PluginResult {
        val namespace = namespace(caller)
            ?: return PluginResult.Failure(403, "SCOPE_REQUIRED", "a verified credential is required")
        return try {
            when (op) {
                "get" -> {
                    val key = keyArgument(args)
                    val value = store.get(namespace, key)
                    if (value == null) {
                        PluginResult.Failure(404, "NOT_FOUND", "no value for that key")
                    } else {
                        PluginResult.Ok(mapOf("key" to key, "value" to value))
                    }
                }

                "put" -> {
                    val key = keyArgument(args)
                    val value = valueArgument(args)
                    store.put(namespace, key, value)
                    PluginResult.Ok(mapOf("stored" to true, "key" to key))
                }

                "delete" -> {
                    val key = keyArgument(args)
                    val deleted = store.delete(namespace, key)
                    if (!deleted) {
                        PluginResult.Failure(404, "NOT_FOUND", "no value for that key")
                    } else {
                        PluginResult.Ok(mapOf("deleted" to true, "key" to key))
                    }
                }

                "list" -> PluginResult.Ok(mapOf("keys" to store.list(namespace)))

                else -> PluginResult.Failure(400, "UNKNOWN_OP", "unknown op")
            }
        } catch (failure: StoreFullException) {
            PluginResult.Failure(409, "STORE_FULL", "the scoped store is full")
        } catch (_: IllegalArgumentException) {
            PluginResult.Failure(400, "INVALID_ARGS", "invalid key or value")
        } catch (_: RuntimeException) {
            // Constant message: an unexpected failure must not leak file paths.
            PluginResult.Failure(500, "STORE_ERROR", "storage failed")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun namespace(caller: SuperAuth.Principal?): String? = when (caller) {
        is SuperAuth.Principal.Operator -> OPERATOR_NAMESPACE
        is SuperAuth.Principal.Peer -> "peer:${caller.nodeId}"
        null -> null
    }

    private fun keyArgument(args: Map<String, Any?>): String {
        val key = args["key"] as? String
            ?: throw IllegalArgumentException("key is required")
        if (key.length !in 1..ScopedStorageStore.MAX_KEY_CHARS) {
            throw IllegalArgumentException("key is too long")
        }
        if (!ScopedStorageStore.KEY_PATTERN.matches(key)) {
            throw IllegalArgumentException("key contains unsupported characters")
        }
        return key
    }

    private fun valueArgument(args: Map<String, Any?>): String {
        val value = args["value"] as? String
            ?: throw IllegalArgumentException("value is required")
        if (value.length > ScopedStorageStore.MAX_VALUE_BYTES) {
            throw IllegalArgumentException("value is too large")
        }
        return value
    }

    private companion object {
        val OPS: Set<String> = setOf("get", "put", "delete", "list")

        /** A peer's key id is 16 lowercase hex, so it can never collide. */
        const val OPERATOR_NAMESPACE: String = "operator"
    }
}
