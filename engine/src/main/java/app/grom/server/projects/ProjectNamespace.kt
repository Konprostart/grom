// SPDX-License-Identifier: Apache-2.0
package app.grom.server.projects

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser

/**
 * Per-project key scoping for the local key/value store (D61).
 *
 * The agent's `kv.*` ops take a single flat key space. Two applications that
 * embed the engine would therefore share it, and the documented promise —
 * "each project has its own namespace" — used to be a convention rather than
 * something the engine enforced. This object makes the prefix real: every key
 * written on behalf of a project is stored as `p:<projectId>:<key>`.
 *
 * A caller may only ever name its own project and can never supply a scoped key
 * that belongs to somebody else: the prefix is computed here, never taken from
 * the request, and a key that already carries a foreign `p:` prefix is refused
 * with `PROJECT_KEY_INVALID`.
 *
 * Scope of the guarantee, stated plainly: this is an ownership gate on the API
 * surface, not a sandbox. Applications that run inside the *same* UID (the
 * embedding model, D55) are one trust domain — a deliberately malicious one
 * could announce another project's id. Preventing that needs separate UIDs or
 * signatures, which is exactly what the standalone + signature model gives you.
 * See docs/EMBEDDING.md §3.
 */
object ProjectNamespace {

    /** Reserved prefix for every project-scoped key. */
    const val PREFIX: String = "p:"

    const val MAX_KEY_CHARS: Int = 128

    /**
     * 32 KiB, not the full 64 KiB request cap: the value travels inside a JSON
     * envelope and may need escaping, so a larger value would fail deep in the
     * agent with `413` instead of being refused here with a clear code.
     */
    const val MAX_VALUE_CHARS: Int = 32 * 1024

    /**
     * Keys are opaque identifiers, not paths: no slashes, no whitespace, no
     * `..` segment, no control characters.
     */
    val KEY_PATTERN: Regex = Regex("^[A-Za-z0-9._:-]{1,$MAX_KEY_CHARS}$")

    /** The only agent ops a project may run through this surface. */
    val ALLOWED_OPS: Set<String> = linkedSetOf("kv.get", "kv.put")

    /**
     * Returns the storage key for [key] owned by [projectId].
     *
     * A key that already starts with `p:` is accepted only when it belongs to
     * [projectId] (so a host can read back what it wrote); anything else is a
     * crossing attempt and is rejected.
     *
     * @throws ApiException `BAD_PROJECT` / `PROJECT_KEY_INVALID`.
     */
    fun scope(projectId: String, key: String): String {
        requireValidProjectId(projectId)
        val trimmed = key.trim()
        validateKey(projectId, trimmed)
        val own = "$PREFIX$projectId:"
        return when {
            trimmed.startsWith(own) -> trimmed
            trimmed.startsWith(PREFIX) -> throw ApiException(
                400,
                "PROJECT_KEY_INVALID",
                "key belongs to another project's namespace"
            )
            else -> own + trimmed
        }
    }

    /** True when [key] is stored inside [projectId]'s namespace. */
    fun owns(projectId: String, key: String): Boolean =
        key.startsWith("$PREFIX$projectId:")

    /**
     * @throws ApiException `PROJECT_KEY_INVALID` when the key cannot be stored.
     */
    fun validateKey(projectId: String, key: String) {
        val bare = normalizedKeyShape(projectId, key)
        if (bare.isEmpty() || bare.contains("..") || !KEY_PATTERN.matches(bare)) {
            throw ApiException(
                400,
                "PROJECT_KEY_INVALID",
                "key must be 1..$MAX_KEY_CHARS of letters, digits, dot, dash, underscore or colon, without '..'"
            )
        }
    }

    /**
     * Builds the agent request body for one project op, or refuses it.
     *
     * This is the whole data path: op whitelist, key ownership and value size
     * are decided here, before a socket exists, and the result is the exact
     * `{"op":…,"args":{"key":"p:<projectId>:…"}}` the agent receives. Keeping it
     * pure is what lets [ProjectNamespaceTest] prove the prefix rather than
     * trusting the caller.
     *
     * @throws ApiException `BAD_PROJECT`, `UNKNOWN_OP`, `BAD_ARGS`,
     *   `PROJECT_KEY_INVALID` or `VALUE_TOO_LARGE`.
     */
    fun buildRequest(projectId: String, op: String, argsJson: String?): String {
        requireValidProjectId(projectId)
        if (op !in ALLOWED_OPS) {
            throw ApiException(
                400,
                "UNKNOWN_OP",
                "project ops are limited to ${ALLOWED_OPS.joinToString(", ")}"
            )
        }
        val args = JsonParser.parseObjectOrNull(argsJson ?: "")
            ?: throw ApiException(400, "BAD_ARGS", "args must be a JSON object")
        val rawKey = (args["key"] as? String)
            ?: throw ApiException(400, "BAD_ARGS", "args.key must be a string")
        val scoped = linkedMapOf<String, Any?>("key" to scope(projectId, rawKey))
        if (op == "kv.put") {
            val value = (args["value"] as? String)
                ?: throw ApiException(400, "BAD_ARGS", "args.value must be a string")
            if (value.length > MAX_VALUE_CHARS) {
                throw ApiException(
                    400,
                    "VALUE_TOO_LARGE",
                    "value must be at most $MAX_VALUE_CHARS characters"
                )
            }
            scoped["value"] = value
        }
        return Json.encode(mapOf("op" to op, "args" to scoped))
    }

    /**
     * @throws ApiException `BAD_PROJECT` when the id is not a project id.
     */
    fun requireValidProjectId(projectId: String) {
        if (!ProjectRecord.ID_PATTERN.matches(projectId)) {
            throw ApiException(
                400,
                "BAD_PROJECT",
                "project id must match ${ProjectRecord.ID_PATTERN.pattern}"
            )
        }
    }

    /**
     * The unscoped part of [key] for validation. A caller-supplied key is
     * allowed to be either `name` or `p:<projectId>:name`; validating the raw
     * string would reject the second form only because of its length.
     */
    private fun normalizedKeyShape(projectId: String, key: String): String {
        val own = "$PREFIX$projectId:"
        val bare = if (key.startsWith(own)) key.removePrefix(own) else key
        return bare
    }
}
