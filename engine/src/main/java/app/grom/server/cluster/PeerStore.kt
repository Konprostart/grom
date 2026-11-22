// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import java.io.File

/**
 * Persistent list of paired devices (D70, D74).
 *
 * Same storage discipline as every other Grom store (D40): one small JSON file,
 * owner-only permissions, write-to-temp-then-rename and a size cap enforced
 * *before* the write. There is no small hard count: pairing is the operator's
 * choice and a low cap would be an arbitrary refusal, so the file size is the
 * real bound (`PEER_STORE_FULL`).
 *
 * A file that cannot be parsed is reported and treated as empty; it is never
 * deleted, because a corrupt file an operator can inspect is better than a
 * silently reset fleet.
 */
class PeerStore(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxFileBytes: Int = MAX_FILE_BYTES
) {

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun list(): List<PeerRecord> = load()

    @Synchronized
    fun find(nodeId: String): PeerRecord? = load().firstOrNull { it.nodeId == nodeId }

    /** Adds or replaces one peer; a re-pairing overwrites the old grant. */
    @Synchronized
    fun add(record: PeerRecord): PeerRecord {
        if (!NodeIdentity.NODE_ID_PATTERN.matches(record.nodeId)) {
            throw ApiException(400, "BAD_PEER", "the peer node id is malformed")
        }
        if (PeerAddress.normalize(record.address) != record.address) {
            throw ApiException(400, "BAD_PEER_ADDRESS", "the peer address is not canonical")
        }
        if (record.grantedByMe.isEmpty()) {
            throw ApiException(400, "SCOPE_REQUIRED", "a pairing must grant at least one scope")
        }
        val records = load().toMutableList()
        records.removeAll { it.nodeId == record.nodeId }
        records.add(record)
        save(records)
        return record
    }

    @Synchronized
    fun remove(nodeId: String) {
        val records = load().toMutableList()
        if (!records.removeAll { it.nodeId == nodeId }) {
            throw ApiException(404, "PEER_NOT_FOUND", "no paired device with that id")
        }
        save(records)
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    /**
     * Records the outcome of one probe. Only measured fields change; the grant
     * and the address are untouched.
     */
    @Synchronized
    fun recordProbe(nodeId: String, ok: Boolean, error: String?): PeerRecord? {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.nodeId == nodeId }
        if (index < 0) return null
        val updated = records[index].copy(
            lastProbeMs = clock(),
            lastProbeOk = ok,
            lastError = error
        )
        records[index] = updated
        save(records)
        return updated
    }

    @Synchronized
    fun rename(nodeId: String, label: String): PeerRecord {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.nodeId == nodeId }
        if (index < 0) throw ApiException(404, "PEER_NOT_FOUND", "no paired device with that id")
        val updated = records[index].copy(label = NodeIdentity.normalizeLabel(label))
        records[index] = updated
        save(records)
        return updated
    }

    // ------------------------------------------------------------------ storage

    private fun load(): List<PeerRecord> {
        lastError = null
        if (!file.isFile) return emptyList()
        return try {
            val text = file.readText()
            if (text.length > maxFileBytes) {
                lastError = "peers file is larger than $maxFileBytes bytes"
                return emptyList()
            }
            val root = JsonParser.parseObjectOrNull(text) ?: run {
                lastError = "peers file is not a JSON object"
                return emptyList()
            }
            val raw = root["peers"] as? List<*> ?: return emptyList()
            val out = ArrayList<PeerRecord>(raw.size)
            for (item in raw) {
                val map = item as? Map<*, *> ?: continue
                val peer = PeerRecord.fromJson(map)
                if (peer == null) {
                    lastError = "a stored peer record was rejected"
                    continue
                }
                out.add(peer)
            }
            out
        } catch (failure: Exception) {
            lastError = "peers unreadable: ${failure.javaClass.simpleName}"
            emptyList()
        }
    }

    private fun save(records: List<PeerRecord>) {
        file.parentFile?.mkdirs()
        val text = Json.encode(
            mapOf(
                "version" to 1,
                "peers" to records.map { it.toJson(clock(), includeAddress = true) }
            )
        )
        // Enforced before the write: an over-size write would make every record
        // unreadable on the next start, which is worse than refusing this one.
        if (text.length > maxFileBytes) {
            throw ApiException(
                409,
                "PEER_STORE_FULL",
                "the paired-device file is full (limit $maxFileBytes bytes)"
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

    companion object {
        const val FILE_NAME: String = "peers.json"

        /** Room for hundreds of pairings; small enough to bound one bad write. */
        const val MAX_FILE_BYTES: Int = 256 * 1024

        /** The UI says "pairwise pairing does not scale" beyond this. */
        const val ADVISORY_PEERS: Int = 8
    }
}
