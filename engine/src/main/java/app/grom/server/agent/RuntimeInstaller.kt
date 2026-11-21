// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Moves the runtime from the APK into app-private storage (D24, S3-R001).
 *
 * The agent script ships inside the APK (`assets/node/grom-agent.js`); the
 * Node binary is supplied by the operator as `assets/node/libnode.so`.
 *
 * This object is pure Kotlin so it is exercised on the JVM: no Android types,
 * and the asset manager is injected as an opener.
 *
 * Rules:
 *  - an existing, valid file is not rewritten on every start (same size);
 *  - a copied binary is validated as an AArch64 ELF *before* it replaces a
 *    working one, so a truncated asset cannot destroy a good runtime;
 *  - installation is best effort: a failure is reported, never thrown, because
 *    a missing runtime is a normal state (S3-R035), not a crash.
 */
object RuntimeInstaller {

    const val BINARY_NAME: String = "libnode.so"
    const val SCRIPT_NAME: String = "grom-agent.js"

    private const val EM_AARCH64: Int = 183
    private const val MAX_RUNTIME_BYTES: Long = 256L * 1024 * 1024
    private const val ELF_HEADER_BYTES: Int = 20

    data class Outcome(
        val installed: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val rejected: List<String> = emptyList(),
        val error: String? = null
    ) {
        val complete: Boolean get() = rejected.isEmpty() && error == null
    }

    /**
     * @param targetDir the runtime directory (normally `<files>/grom/node`)
     * @param names     files to install, in order
     * @param open      returns the source stream for a name, or null when the
     *                  asset is not present
     */
    fun install(
        targetDir: File,
        names: List<String> = listOf(BINARY_NAME, SCRIPT_NAME),
        open: (String) -> InputStream?
    ): Outcome {
        val installed = ArrayList<String>()
        val skipped = ArrayList<String>()
        val rejected = ArrayList<String>()

        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            return Outcome(error = "cannot create ${targetDir.absolutePath}")
        }

        for (name in names) {
            val target = File(targetDir, name)
            val source = openOrNull(open, name)
            if (source == null) {
                if (target.isFile) skipped += name else rejected += name
                continue
            }

            source.use { stream ->
                val temporary = File(targetDir, ".$name.tmp")
                temporary.delete()

                if (!copyBounded(stream, temporary)) {
                    temporary.delete()
                    rejected += name
                    return@use
                }
                if (name == BINARY_NAME && !isAarch64Elf(temporary)) {
                    temporary.delete()
                    rejected += name
                    return@use
                }
                if (isCurrent(target, temporary)) {
                    temporary.delete()
                    skipped += name
                    return@use
                }
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    rejected += name
                    return@use
                }
                installed += name
            }
        }

        val binary = File(targetDir, BINARY_NAME)
        if (binary.isFile) binary.setExecutable(true, false)

        return Outcome(installed = installed, skipped = skipped, rejected = rejected)
    }

    private fun openOrNull(open: (String) -> InputStream?, name: String): InputStream? =
        try {
            open(name)
        } catch (_: Exception) {
            null
        }

    /** Copy with a hard upper bound; a runaway stream must not fill the device. */
    private fun copyBounded(source: InputStream, target: File): Boolean {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        BufferedOutputStream(FileOutputStream(target)).use { output ->
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_RUNTIME_BYTES) return false
                output.write(buffer, 0, read)
            }
        }
        return total > 0
    }

    /** Same size and, for the binary, already executable: keep what is there. */
    private fun isCurrent(target: File, candidate: File): Boolean =
        target.isFile && target.length() == candidate.length() &&
            (target.name != BINARY_NAME || target.canExecute())

    /**
     * Minimal ELF check: magic plus `e_machine == AArch64`. Endianness is read
     * from the header rather than assumed.
     */
    fun isAarch64Elf(file: File): Boolean {
        if (!file.isFile || file.length() < ELF_HEADER_BYTES) return false
        val head = ByteArray(ELF_HEADER_BYTES)
        val read = file.inputStream().use { it.read(head) }
        if (read < ELF_HEADER_BYTES) return false
        if (head[0].toInt() and 0xFF != 0x7F) return false
        if (head[1].toInt() and 0xFF != 'E'.code) return false
        if (head[2].toInt() and 0xFF != 'L'.code) return false
        if (head[3].toInt() and 0xFF != 'F'.code) return false
        val littleEndian = head[5].toInt() == 1
        val machine = if (littleEndian) {
            (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        } else {
            ((head[18].toInt() and 0xFF) shl 8) or (head[19].toInt() and 0xFF)
        }
        return machine == EM_AARCH64
    }
}
