// SPDX-License-Identifier: Apache-2.0
package app.grom.server.store

import app.grom.server.core.Token
import java.io.File

/**
 * Token persistence in app-private storage (D8, S1-R024).
 *
 * The file lives under the app's private directory; the directory is already
 * readable only by the app UID on Android. We additionally try to tighten the
 * POSIX mode to 0600 and ignore the failure where the filesystem does not
 * support it (some Android storage layers do not expose POSIX modes).
 */
class SecureStore(private val directory: File) {

    private val tokenFile = File(directory, TOKEN_FILE)

    @Synchronized
    fun loadOrCreate(): String {
        val existing = readToken()
        if (existing != null) return existing
        return newToken()
    }

    @Synchronized
    fun current(): String? = readToken()

    @Synchronized
    fun rotate(): String = newToken()

    private fun newToken(): String {
        val token = Token.generate()
        directory.mkdirs()
        tokenFile.writeText(token + "\n")
        harden(tokenFile)
        return token
    }

    private fun readToken(): String? {
        if (!tokenFile.isFile) return null
        val value = tokenFile.readText().trim()
        return if (Token.hasValidShape(value)) value else null
    }

    private fun harden(file: File) {
        runCatching { file.setReadable(false, false) }
        runCatching { file.setReadable(true, true) }
        runCatching { file.setWritable(false, false) }
        runCatching { file.setWritable(true, true) }
    }

    companion object {
        const val TOKEN_FILE: String = "grom-token"
    }
}
