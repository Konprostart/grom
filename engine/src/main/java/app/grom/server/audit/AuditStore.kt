// SPDX-License-Identifier: Apache-2.0
package app.grom.server.audit

import app.grom.server.core.AuditChain
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Append-only audit storage on disk (D10, D27, S2-R033…S2-R035).
 *
 * Format: one entry per line, tab separated:
 *   seq \t ts \t actor \t action \t detail \t prev \t hash
 * with actor/action/detail percent-encoded so a value can never contain a tab
 * or a newline and break the line format.
 *
 * `audit.head` stores the last (seq, hash) so a rotation does not break the
 * chain: the first entry after rotation links to the previous segment's head.
 */
class AuditStore(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {

    private val current = File(directory, "audit.log")
    private val previous = File(directory, "audit.log.1")
    private val head = File(directory, "audit.head")
    private val baseFile = File(directory, "audit.base")

    private var cachedHead: AuditChain.Entry? = null
    private var base: String = AuditChain.GENESIS
    private var loaded = false

    @Synchronized
    fun append(actor: String, action: String, detail: String, ts: Long = System.currentTimeMillis()): AuditChain.Entry {
        directory.mkdirs()
        ensureLoaded()
        val entry = AuditChain.next(cachedHead, ts, actor, action, detail)
        val line = encode(entry) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        rotateIfNeeded(bytes.size.toLong())
        FileOutputStream(current, true).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        writeHead(entry)
        cachedHead = entry
        return entry
    }

    @Synchronized
    fun tail(limit: Int): List<AuditChain.Entry> {
        ensureLoaded()
        val all = readAll()
        if (limit <= 0) return emptyList()
        return if (all.size <= limit) all else all.subList(all.size - limit, all.size)
    }

    @Synchronized
    fun verify(): AuditChain.VerifyResult {
        ensureLoaded()
        return AuditChain.verify(readAll(), base)
    }

    @Synchronized
    fun headHash(): String {
        ensureLoaded()
        return cachedHead?.hash ?: AuditChain.GENESIS
    }

    @Synchronized
    fun headSeq(): Long {
        ensureLoaded()
        return cachedHead?.seq ?: 0L
    }

    private fun ensureLoaded() {
        if (loaded) return
        directory.mkdirs()
        base = readBase() ?: AuditChain.GENESIS
        cachedHead = readHeadFile() ?: readAll().lastOrNull()
        loaded = true
    }

    private fun rotateIfNeeded(incoming: Long) {
        if (!current.exists()) return
        if (current.length() + incoming <= maxBytes) return

        // Only one generation is kept (D27), so the oldest segment is dropped.
        // Record the prev-hash of the new first entry so verification still has
        // an anchor and a truncated chain is not reported as tampered.
        val newBase = readFile(current).firstOrNull()?.prev ?: base

        previous.delete()
        if (!current.renameTo(previous)) {
            // Fall back to copy+truncate; the chain stays valid because the head
            // file already records what the next entry must link to.
            previous.writeBytes(current.readBytes())
            RandomAccessFile(current, "rw").use { it.setLength(0) }
        }
        base = newBase
        writeBase(newBase)
    }

    private fun writeHead(entry: AuditChain.Entry) {
        head.writeText("${entry.seq}\t${entry.hash}\n")
    }

    private fun writeBase(value: String) {
        baseFile.writeText(value + "\n")
    }

    private fun readBase(): String? {
        if (!baseFile.isFile) return null
        val text = baseFile.readText().trim()
        return if (text.length == 64) text else null
    }

    private fun readHeadFile(): AuditChain.Entry? {
        if (!head.isFile) return null
        val text = head.readText().trim()
        if (text.isEmpty()) return null
        val parts = text.split("\t")
        if (parts.size != 2) return null
        val seq = parts[0].toLongOrNull() ?: return null
        val hashHex = parts[1]
        if (hashHex.length != 64) return null
        // seq/ts/... are unknown from the head file alone; only seq and hash
        // matter for chaining, so this placeholder is never written back.
        return AuditChain.Entry(seq, 0L, "", "", "", "", hashHex)
    }

    private fun readAll(): List<AuditChain.Entry> {
        val entries = ArrayList<AuditChain.Entry>()
        entries.addAll(readFile(previous))
        entries.addAll(readFile(current))
        return entries
    }

    private fun readFile(file: File): List<AuditChain.Entry> {
        if (!file.isFile) return emptyList()
        val result = ArrayList<AuditChain.Entry>()
        file.forEachLine { line ->
            if (line.isNotEmpty()) {
                decode(line)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun encode(entry: AuditChain.Entry): String = listOf(
        entry.seq.toString(),
        entry.ts.toString(),
        encodeComponent(entry.actor),
        encodeComponent(entry.action),
        encodeComponent(entry.detail),
        entry.prev,
        entry.hash
    ).joinToString("\t")

    private fun decode(line: String): AuditChain.Entry? {
        val parts = line.split("\t")
        if (parts.size != 7) return null
        return try {
            AuditChain.Entry(
                seq = parts[0].toLong(),
                ts = parts[1].toLong(),
                actor = decodeComponent(parts[2]),
                action = decodeComponent(parts[3]),
                detail = decodeComponent(parts[4]),
                prev = parts[5],
                hash = parts[6]
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun decodeComponent(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 4L * 1024 * 1024
    }
}
