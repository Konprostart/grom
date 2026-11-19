// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import java.security.MessageDigest

/**
 * Append-only hash chain (D10, D28, S1-R015).
 *
 * hash = SHA-256( domain || len(field):field ... ) over
 *        (seq, ts, actor, action, detail, prev)
 *
 * Length prefixes make the encoding unambiguous even when a detail string
 * contains the separator or a newline.
 */
object AuditChain {

    const val DOMAIN: String = "grom.audit.v1"
    val GENESIS: String = "0".repeat(64)

    data class Entry(
        val seq: Long,
        val ts: Long,
        val actor: String,
        val action: String,
        val detail: String,
        val prev: String,
        val hash: String
    )

    data class VerifyResult(
        val ok: Boolean,
        val entries: Int,
        val head: String,
        val brokenAt: Long?
    )

    fun hash(seq: Long, ts: Long, actor: String, action: String, detail: String, prev: String): String {
        val sb = StringBuilder()
        sb.append(field(DOMAIN))
        sb.append(field(seq.toString()))
        sb.append(field(ts.toString()))
        sb.append(field(actor))
        sb.append(field(action))
        sb.append(field(detail))
        sb.append(field(prev))
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return toHex(digest)
    }

    fun next(
        previous: Entry?,
        ts: Long,
        actor: String,
        action: String,
        detail: String
    ): Entry {
        val seq = (previous?.seq ?: 0L) + 1L
        val prev = previous?.hash ?: GENESIS
        return Entry(seq, ts, actor, action, detail, prev, hash(seq, ts, actor, action, detail, prev))
    }

    fun verify(entries: List<Entry>, expectedFirstPrev: String = GENESIS): VerifyResult {
        var previous: Entry? = null
        var first = true
        for (entry in entries) {
            val expectedPrev = if (first) expectedFirstPrev else previous?.hash ?: GENESIS
            first = false
            if (entry.prev != expectedPrev) {
                return VerifyResult(false, entries.size, previous?.hash ?: GENESIS, entry.seq)
            }
            val expectedHash = hash(entry.seq, entry.ts, entry.actor, entry.action, entry.detail, entry.prev)
            if (entry.hash != expectedHash) {
                return VerifyResult(false, entries.size, previous?.hash ?: GENESIS, entry.seq)
            }
            previous = entry
        }
        return VerifyResult(true, entries.size, previous?.hash ?: GENESIS, null)
    }

    private fun field(value: String): String = "${value.length}:$value"

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
