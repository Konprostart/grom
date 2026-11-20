// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import java.io.File

/**
 * Atomic persistence of [SuperServerConfig] in app-private storage (D40).
 *
 * The password is stored only as a PBKDF2 hash and API keys as public keys, so
 * the file itself holds no usable credential. The Lightning backend API key is
 * additionally sealed by [app.grom.server.store.SecretBox] before it is written.
 *
 * A corrupt file is *not* fatal: the server falls back to the safe defaults and
 * reports the reason, so a mangled config can never turn into an open listener.
 */
class SuperServerConfigStore(private val directory: File) {

    private val file = File(directory, FILE_NAME)

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun load(): SuperServerConfig {
        lastError = null
        if (!file.isFile) return SuperServerConfig.defaults()
        return try {
            val text = file.readText()
            if (text.length > MAX_FILE_BYTES) {
                lastError = "config file is larger than $MAX_FILE_BYTES bytes"
                SuperServerConfig.defaults()
            } else {
                SuperServerConfig.fromJson(text) ?: run {
                    lastError = "config file is not a JSON object"
                    SuperServerConfig.defaults()
                }
            }
        } catch (failure: Exception) {
            lastError = "config unreadable: ${failure.javaClass.simpleName}"
            SuperServerConfig.defaults()
        }
    }

    @Synchronized
    fun save(config: SuperServerConfig) {
        directory.mkdirs()
        val text = config.toJson()
        val temp = File(directory, "$FILE_NAME.tmp")
        // Write-then-rename: a crash mid-write leaves the previous config intact,
        // never a half-parsed one that might silently relax a policy.
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

    companion object {
        const val FILE_NAME: String = "super-server.json"
        private const val MAX_FILE_BYTES: Int = 64 * 1024
    }
}
