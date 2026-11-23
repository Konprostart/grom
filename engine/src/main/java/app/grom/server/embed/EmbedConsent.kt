// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

/**
 * What the operator agreed to, and whether that agreement still covers what the
 * host is about to start (D88).
 *
 * A boolean is not consent. A host that recorded `true` once would keep starting
 * a persistent server after it changed *what* the server does — a new data
 * plane, an extra project, a different notice — and the operator's answer would
 * silently apply to the new behaviour. Recording the version of the notice the
 * operator actually read, and refusing a start that declares a different one,
 * makes "you agreed to this" a statement about a specific text.
 *
 * The engine owns no wording (D55): the host writes and localizes the notice and
 * chooses its version string. The engine only stores the pair (version, time) in
 * the host's private preferences and compares it.
 *
 * Pure Kotlin, so every branch below is a JVM test (D30).
 */
object EmbedConsent {

    /** No consent has been recorded: the host must show its notice first. */
    const val REQUIRED: String = "CONSENT_REQUIRED"

    /** A consent exists, but for different notice text (D88). */
    const val OUTDATED: String = "CONSENT_OUTDATED"

    /** One recorded answer. An empty [noticeVersion] means the host does not version. */
    data class Record(val noticeVersion: String, val grantedAtMs: Long)

    /**
     * The record to compare, from what the host stored.
     *
     * @param granted the pre-D88 boolean flag; `false` means nothing was recorded.
     * @param noticeVersion the version stored beside the flag, empty when the
     *   host has never declared one.
     */
    fun record(granted: Boolean, noticeVersion: String, grantedAtMs: Long): Record? =
        if (granted) Record(noticeVersion.trim(), grantedAtMs) else null

    /**
     * The refusal code for this start, or `null` when consent covers it.
     *
     * @param record what the host stored, or `null` when nothing was recorded.
     * @param requestedNoticeVersion the version the host is starting now; empty
     *   means "this host does not version its notice", which is the behaviour
     *   every host had before D88 and must keep working.
     */
    fun refusal(record: Record?, requestedNoticeVersion: String): String? {
        if (record == null) return REQUIRED
        val requested = requestedNoticeVersion.trim()
        if (requested.isEmpty()) return null
        if (record.noticeVersion == requested) return null
        return OUTDATED
    }
}
