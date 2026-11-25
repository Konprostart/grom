// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.plugins.storage

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import java.io.File

/**
 * Persistent, per-namespace key/value storage for connected apps (D92 storage).
 *
 * A namespace is the authenticated caller's identity (an Ed25519 key id for a
 * scoped peer, or the reserved `operator` token for the operator), so two apps
 * can never read or write each other's keys. Only the caller's own namespace is
 * ever addressed; the store has no cross-namespace operations.
 *
 * Storage discipline is identical to every other Grom store (D40): one small
 * JSON file, owner-only permissions, write-to-temp-then-rename and a hard size
 * cap. A corrupt or oversized file is reported through [lastError] and treated
 * as empty rather than crashing the data plane.
 */
class ScopedStorageStore(
    private val file: File,
    private val maxFileBytes: Int = MAX_FILE_BYTES,
    private val maxValueBytes: Int = MAX_VALUE_BYTES
) {

    @Volatile
    var lastError: String? = null
        private set

    /** Returns the stored value, or null when the key has no value. */
    @Synchronized
    fun get(namespace: String, key: String): String? {
        requireKey(key)
        return load().namespaces[namespace]?.get(key)
    }

    /** Sorted keys of the caller's namespace only. */
    @Synchronized
    fun list(namespace: String): List<String> =
        load().namespaces[namespace]?.keys?.sorted() ?: emptyList()

    @Synchronized
    fun put(namespace: String, key: String, value: String) {
        requireKey(key)
        require(value.length <= maxValueBytes) { "value is too large" }
        val snapshot = load()
        val namespaces = snapshot.namespaces.toMutableMap()
        val entries = (namespaces[namespace] ?: emptyMap()).toMutableMap()
        entries[key] = value
        namespaces[namespace] = entries
        save(Snapshot(namespaces))
    }

    /** Returns `false` when the key had no value. */
    @Synchronized
    fun delete(namespace: String, key: String): Boolean {
        requireKey(key)
        val snapshot = load()
        val existing = snapshot.namespaces[namespace] ?: return false
        val entries = existing.toMutableMap()
        if (entries.remove(key) == null) return false
        val namespaces = snapshot.namespaces.toMutableMap()
        if (entries.isEmpty()) namespaces.remove(namespace) else namespaces[namespace] = entries
        save(Snapshot(namespaces))
        return true
    }

    /** A non-secret summary: counts and total bytes, never another app's keys. */
    @Synchronized
    fun summary(): Map<String, Any?> {
        val snapshot = load()
        return mapOf(
            "namespaces" to snapshot.namespaces.size,
            "keys" to snapshot.namespaces.values.sumOf { it.size },
            "bytes" to (if (file.isFile) file.length() else 0L)
        )
    }

    // ------------------------------------------------------------------ storage

    private data class Snapshot(val namespaces: Map<String, Map<String, String>>)

    private fun load(): Snapshot {
        lastError = null
        if (!file.isFile) return Snapshot(emptyMap())
        return try {
            val text = file.readText()
            if (text.length > maxFileBytes) {
                lastError = "scoped storage file is larger than $maxFileBytes bytes"
                return Snapshot(emptyMap())
            }
            val root = JsonParser.parseObjectOrNull(text)
            if (root == null) {
                lastError = "scoped storage file is not a JSON object"
                return Snapshot(emptyMap())
            }
            val rawNamespaces = root["namespaces"] as? Map<*, *>
                ?: return Snapshot(emptyMap())
            val namespaces = LinkedHashMap<String, Map<String, String>>()
            for ((namespaceKey, rawEntries) in rawNamespaces) {
                val namespace = namespaceKey as? String ?: continue
                val entries = rawEntries as? Map<*, *> ?: continue
                val out = LinkedHashMap<String, String>()
                for ((key, value) in entries) {
                    val keyText = key as? String ?: continue
                    val valueText = value as? String ?: continue
                    if (!KEY_PATTERN.matches(keyText)) continue
                    out[keyText] = valueText
                }
                namespaces[namespace] = out
            }
            Snapshot(namespaces)
        } catch (failure: Exception) {
            lastError = "scoped storage unreadable: ${failure.javaClass.simpleName}"
            Snapshot(emptyMap())
        }
    }

    private fun save(snapshot: Snapshot) {
        file.parentFile?.mkdirs()
        val text = Json.encode(
            mapOf(
                "version" to 1,
                "namespaces" to snapshot.namespaces.mapValues { (_, entries) -> entries }
            )
        )
        if (text.length > maxFileBytes) {
            throw StoreFullException(
                "scoped storage is full (limit $maxFileBytes bytes); delete some keys first"
            )
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        harden(temp)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        harden(file)
    }

    private fun harden(target: File) {
        runCatching { target.setReadable(false, false) }
        runCatching { target.setReadable(true, true) }
        runCatching { target.setWritable(false, false) }
        runCatching { target.setWritable(true, true) }
    }

    private fun requireKey(key: String) {
        require(key.length in 1..MAX_KEY_CHARS) { "key must be 1..$MAX_KEY_CHARS characters" }
        require(KEY_PATTERN.matches(key)) { "key must match ${KEY_PATTERN.pattern}" }
    }

    companion object {
        const val FILE_NAME: String = "scoped-storage.json"

        /** A key is a small, URL-safe token so it stays portable across hosts. */
        const val MAX_KEY_CHARS: Int = 128
        val KEY_PATTERN: Regex = Regex("^[A-Za-z0-9._-]{1,128}$")

        /**
         * 16 KiB per value keeps one entry small while allowing real payloads;
         * 512 KiB of JSON total bounds the whole file the way every other store
         * does (D40).
         */
        const val MAX_VALUE_BYTES: Int = 16 * 1024
        const val MAX_FILE_BYTES: Int = 512 * 1024
    }
}

/** The scoped storage file has reached its hard size cap. */
class StoreFullException(message: String) : RuntimeException(message)
