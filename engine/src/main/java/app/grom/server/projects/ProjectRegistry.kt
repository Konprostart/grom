// SPDX-License-Identifier: Apache-2.0
package app.grom.server.projects

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import java.io.File

/**
 * A project registered by the application that is running inside or beside the
 * Grom engine (D55).
 *
 * A record is a *claim by the host*: the SDK name, version and the capabilities
 * it says it uses. Grom does not verify the claim, and registration is never
 * evidence that something is running. [state] is derived from the heartbeat, so
 * an app that stops announcing becomes `stale` on its own and no field can make
 * the dashboard green by itself.
 */
data class ProjectRecord(
    val id: String,
    val title: String,
    val version: String,
    val packageName: String,
    val capabilities: List<String>,
    val registeredAtMs: Long,
    val lastSeenMs: Long
) {

    /** `alive` only while the host keeps announcing; never set by a caller. */
    fun state(nowMs: Long): String =
        if (nowMs - lastSeenMs <= STALE_AFTER_MS) STATE_ALIVE else STATE_STALE

    fun toJson(nowMs: Long): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "title" to title,
        "version" to version,
        "package" to packageName,
        "capabilities" to capabilities,
        "registeredAtMs" to registeredAtMs,
        "lastSeenMs" to lastSeenMs,
        "state" to state(nowMs)
    )

    companion object {
        const val STALE_AFTER_MS: Long = 90_000

        const val STATE_ALIVE: String = "alive"
        const val STATE_STALE: String = "stale"

        /** Same shape as a plugin id, so a project cannot smuggle a path. */
        val ID_PATTERN: Regex = Regex("^[a-z][a-z0-9-]{1,31}$")

        const val MAX_TITLE_CHARS: Int = 64
        const val MAX_VERSION_CHARS: Int = 32
        const val MAX_PACKAGE_CHARS: Int = 128
        const val MAX_CAPABILITY_CHARS: Int = 32
        const val MAX_CAPABILITIES: Int = 12

        val CAPABILITY_PATTERN: Regex = Regex("^[a-z][a-z0-9.\\-]{0,31}$")
        val PACKAGE_PATTERN: Regex = Regex("^[A-Za-z][A-Za-z0-9_.]{0,127}$")

        fun fromJson(raw: Map<*, *>): ProjectRecord? {
            val id = raw["id"] as? String ?: return null
            val title = raw["title"] as? String ?: return null
            val version = raw["version"] as? String ?: ""
            val pkg = raw["package"] as? String ?: ""
            val capabilities = (raw["capabilities"] as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf { value -> value.isNotEmpty() } }
                ?: emptyList()
            val registeredAt = (raw["registeredAtMs"] as? Number)?.toLong() ?: 0L
            val lastSeen = (raw["lastSeenMs"] as? Number)?.toLong() ?: registeredAt
            return ProjectRecord(id, title, version, pkg, capabilities, registeredAt, lastSeen)
        }
    }
}

/**
 * Persistent registry of projects that announced themselves to the engine.
 *
 * Storage follows the same rules as every other Grom store (D40): one small
 * JSON file, owner-only permissions, written to a temp file and renamed, with
 * a hard size cap. The registry contains no secret: an id, a display title, a
 * version, a package name and a capability list.
 *
 * `announce` is the only write path. It re-validates every field, so a project
 * that sends a hostile id, an over-long title or a path-like package never
 * reaches the file.
 */
class ProjectRegistry(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun list(): List<ProjectRecord> =
        load().sortedWith(compareBy({ it.title.lowercase() }, { it.id }))

    /** JSON-ready list for status documents; contains no secret. */
    fun summary(): List<Map<String, Any?>> {
        val now = clock()
        return list().map { it.toJson(now) }
    }

    /**
     * Creates or refreshes a registration. Returns the stored record.
     *
     * @throws ApiException with `BAD_PROJECT` or `TOO_MANY_PROJECTS`.
     */
    @Synchronized
    fun announce(raw: Map<String, Any?>): ProjectRecord {
        val id = (raw["id"] as? String)?.trim().orEmpty()
        if (!ProjectRecord.ID_PATTERN.matches(id)) {
            throw ApiException(
                400,
                "BAD_PROJECT",
                "project id must match ${ProjectRecord.ID_PATTERN.pattern}"
            )
        }
        val title = checkedText(raw["title"], "title", ProjectRecord.MAX_TITLE_CHARS)
            ?: throw ApiException(400, "BAD_PROJECT", "project title is required")
        val version = checkedText(raw["version"], "version", ProjectRecord.MAX_VERSION_CHARS) ?: ""
        val packageName = (raw["package"] as? String)?.trim().orEmpty()
        if (packageName.isNotEmpty() &&
            (packageName.length > ProjectRecord.MAX_PACKAGE_CHARS ||
                !ProjectRecord.PACKAGE_PATTERN.matches(packageName))
        ) {
            throw ApiException(400, "BAD_PROJECT", "project package name is not valid")
        }
        val capabilities = parseCapabilities(raw["capabilities"])

        val now = clock()
        val records = load().toMutableList()
        val existing = records.firstOrNull { it.id == id }
        val record = ProjectRecord(
            id = id,
            title = title,
            version = version,
            packageName = packageName,
            capabilities = capabilities,
            registeredAtMs = existing?.registeredAtMs ?: now,
            lastSeenMs = now
        )
        if (existing == null && records.size >= MAX_PROJECTS) {
            throw ApiException(
                409,
                "TOO_MANY_PROJECTS",
                "at most $MAX_PROJECTS projects may be registered"
            )
        }
        records.removeAll { it.id == id }
        records.add(record)
        save(records)
        return record
    }

    @Synchronized
    fun remove(id: String) {
        if (!ProjectRecord.ID_PATTERN.matches(id)) {
            // Removing an unknown id is a no-op, but a malformed id is a mistake
            // the caller should see rather than one that silently succeeds.
            throw ApiException(400, "BAD_PROJECT", "project id is not valid")
        }
        val records = load().toMutableList()
        val removed = records.removeAll { it.id == id }
        if (!removed) throw ApiException(404, "PROJECT_NOT_FOUND", "no project with that id")
        save(records)
    }

    // ------------------------------------------------------------------ storage

    private fun load(): List<ProjectRecord> {
        lastError = null
        if (!file.isFile) return emptyList()
        return try {
            val text = file.readText()
            if (text.length > MAX_FILE_BYTES) {
                lastError = "projects file is larger than $MAX_FILE_BYTES bytes"
                return emptyList()
            }
            val root = JsonParser.parseObjectOrNull(text)
            if (root == null) {
                lastError = "projects file is not a JSON object"
                return emptyList()
            }
            val list = root["projects"] as? List<*> ?: return emptyList()
            list.mapNotNull { item ->
                (item as? Map<*, *>)?.let { ProjectRecord.fromJson(it) }
            }.filter { ProjectRecord.ID_PATTERN.matches(it.id) }
        } catch (failure: Exception) {
            lastError = "projects unreadable: ${failure.javaClass.simpleName}"
            emptyList()
        }
    }

    private fun save(records: List<ProjectRecord>) {
        file.parentFile?.mkdirs()
        val text = Json.encode(
            mapOf(
                "version" to 1,
                "projects" to records.map {
                    mapOf(
                        "id" to it.id,
                        "title" to it.title,
                        "version" to it.version,
                        "package" to it.packageName,
                        "capabilities" to it.capabilities,
                        "registeredAtMs" to it.registeredAtMs,
                        "lastSeenMs" to it.lastSeenMs
                    )
                }
            )
        )
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

    // --------------------------------------------------------------- validation

    private fun checkedText(value: Any?, field: String, max: Int): String? {
        val text = (value as? String)?.trim() ?: return null
        if (text.isEmpty()) return null
        if (text.length > max) {
            throw ApiException(400, "BAD_PROJECT", "$field must be at most $max characters")
        }
        if (text.any { it.isISOControl() }) {
            throw ApiException(400, "BAD_PROJECT", "$field must not contain control characters")
        }
        return text
    }

    private fun parseCapabilities(raw: Any?): List<String> {
        if (raw == null) return emptyList()
        val list = raw as? List<*>
            ?: throw ApiException(400, "BAD_PROJECT", "capabilities must be a list")
        if (list.size > ProjectRecord.MAX_CAPABILITIES) {
            throw ApiException(
                400,
                "BAD_PROJECT",
                "at most ${ProjectRecord.MAX_CAPABILITIES} capabilities may be declared"
            )
        }
        val out = LinkedHashSet<String>()
        for (item in list) {
            val name = (item as? String)?.trim().orEmpty()
            if (!ProjectRecord.CAPABILITY_PATTERN.matches(name)) {
                throw ApiException(400, "BAD_PROJECT", "capability '$name' is not valid")
            }
            out.add(name)
        }
        return out.toList()
    }

    companion object {
        const val FILE_NAME: String = "projects.json"
        const val MAX_PROJECTS: Int = 8
        private const val MAX_FILE_BYTES: Int = 64 * 1024
    }
}
