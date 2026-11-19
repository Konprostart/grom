// SPDX-License-Identifier: Apache-2.0
package app.grom.server.update

import app.grom.server.http.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The update check the operator sees in the app.
 *
 * Grom is distributed as APKs attached to GitHub releases, so "is there an
 * update?" is one HTTPS GET against the public releases API — no account, no
 * identifier, no analytics, and nothing is downloaded automatically (S1-R003:
 * the app never reaches the network on its own except for this explicit
 * operator action). A failed check is reported as a failure; it never claims
 * "up to date".
 */
object UpdateChecker {

    const val REPO = "sveatoken/grom"
    const val RELEASES_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val SERVER_ASSET = "server29"
    const val PERIPHERAL_ASSET = "peripheral26"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_BODY_BYTES = 512 * 1024

    /** A published release, reduced to what the app needs. */
    data class ReleaseInfo(
        val tag: String,
        val version: String,
        val title: String,
        val notes: String,
        val htmlUrl: String,
        val apkUrl: String?
    )

    /**
     * A version as the app compares it: `v` prefix and the role suffix are
     * packaging details, not version numbers. `0.2.0-server29` → `0.2.0`.
     */
    fun normalizeVersion(raw: String): String =
        raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()

    /** True only when [candidate] is strictly newer than [current]. */
    fun isNewer(current: String, candidate: String): Boolean {
        val a = numericParts(current) ?: return false
        val b = numericParts(candidate) ?: return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (right > left) return true
            if (right < left) return false
        }
        return false
    }

    private fun numericParts(raw: String): List<Long>? {
        val normalized = normalizeVersion(raw)
        if (normalized.isEmpty()) return null
        val parts = normalized.split('.')
        if (parts.size > 4) return null
        return parts.map { part ->
            part.toLongOrNull() ?: return null
        }
    }

    /**
     * Reads a release document. Returns null when the document has no usable
     * `tag_name` — callers treat that as a failed check, never as "current".
     */
    fun parseRelease(json: String, serverRole: Boolean = true): ReleaseInfo? {
        val root = JsonParser.parseObjectOrNull(json) ?: return null
        val tag = (root["tag_name"] as? String)?.trim().orEmpty()
        if (tag.isEmpty()) return null
        val version = normalizeVersion(tag).ifEmpty { tag }
        val assets = (root["assets"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            .orEmpty()
        return ReleaseInfo(
            tag = tag,
            version = version,
            title = (root["name"] as? String)?.trim().orEmpty().ifEmpty { tag },
            notes = (root["body"] as? String).orEmpty(),
            htmlUrl = (root["html_url"] as? String).orEmpty(),
            apkUrl = selectApk(assets, serverRole)
        )
    }

    private fun selectApk(assets: List<Map<*, *>>, serverRole: Boolean): String? {
        val wanted = if (serverRole) SERVER_ASSET else PERIPHERAL_ASSET
        val apks = assets.mapNotNull { asset ->
            val name = asset["name"] as? String ?: return@mapNotNull null
            val url = asset["browser_download_url"] as? String ?: return@mapNotNull null
            if (name.endsWith(".apk", ignoreCase = true)) name to url else null
        }
        return apks.firstOrNull { it.first.contains(wanted, ignoreCase = true) }?.second
            ?: apks.firstOrNull()?.second
    }

    /**
     * Performs the check. `Result.success(null)` means the app is current;
     * a non-null release is strictly newer; `Result.failure` means the check
     * itself did not complete (offline, rate-limited, malformed response).
     */
    fun check(
        currentVersion: String,
        serverRole: Boolean = true,
        endpoint: String = RELEASES_API,
        fetch: (String) -> String = { httpGet(it) }
    ): Result<ReleaseInfo?> = runCatching {
        val release = parseRelease(fetch(endpoint), serverRole)
            ?: throw IOException("release document had no tag_name")
        if (isNewer(currentVersion, release.version)) release else null
    }

    fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Grom-Android")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("update check failed: HTTP $code")
            }
            val stream = connection.inputStream
            val body = stream.use { input ->
                val buffer = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BODY_BYTES) throw IOException("release document too large")
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}
