// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import app.grom.server.doctor.DoctorCheck
import app.grom.server.doctor.DoctorLevel
import app.grom.server.doctor.DoctorReport
import app.grom.server.doctor.DoctorRules
import app.grom.server.doctor.DoctorVerdict

/**
 * What an embedding host should tell its operator, in one value (D90).
 *
 * The host owns the screens (D55), and before this existed every host had to do
 * three things by hand to answer "is my server all right?" — call `statusJson`,
 * call `doctorJson`, and translate `BATTERY_OPTIMISED` / `EXIT_LOW_MEMORY` into a
 * sentence. Most hosts would do the first two and invent the third, which is how
 * a green "everything is fine" appears on a phone that is killing the service.
 *
 * [EmbedHealth] is a **projection of the Doctor's own rules**, never a second
 * diagnosis: the states and codes come from [DoctorRules], so the host cannot
 * disagree with the Doctor screen, and no branch here can be greener than the
 * report it was built from.
 *
 * Deliberately absent: user-visible sentences and any `android.*` import. The
 * engine does not own the host's wording or translations; it owns stable codes,
 * and this file is pure Kotlin so every mapping is a JVM test (D30).
 */
data class EmbedHealth(
    val state: EmbedState,
    /** Stable headline code; the host maps it to a localized sentence. */
    val headline: String,
    /** What the operator can actually do, most useful first, at most [MAX_ADVICE]. */
    val advice: List<EmbedAdvice> = emptyList(),
    /** Wall clock of the measurement, `0` when nothing was measured. */
    val measuredAtMs: Long = 0L,
    val counts: Counts = Counts()
) {
    /** The measured totals, so a host can say "2 of 10 checks need attention". */
    data class Counts(
        val ok: Int = 0,
        val bad: Int = 0,
        val notice: Int = 0,
        val unknown: Int = 0
    ) {
        val total: Int get() = ok + bad + notice + unknown
    }

    companion object {
        /** A short list; beyond four the operator stops reading and the screen lies by omission. */
        const val MAX_ADVICE: Int = 4

        // -------------------------------------------------------- headlines

        /** Every check measured healthy. This is the only state that may look green. */
        const val SERVER_RUNNING: String = "SERVER_RUNNING"

        /** The process is starting; nothing is wrong yet and nothing is proven yet. */
        const val SERVER_STARTING: String = "SERVER_STARTING"

        /** The server is not running and the last end was clean. */
        const val SERVER_STOPPED: String = "SERVER_STOPPED"

        /** A previous process ended without `onDestroy` (D83). */
        const val SERVER_DIED: String = "SERVER_DIED"

        /** The supervisor stopped restarting after repeated crashes. */
        const val SERVER_QUARANTINED: String = "SERVER_QUARANTINED"

        /** The arm64 Node runtime is not packaged; the server runs without the agent. */
        const val RUNTIME_MISSING: String = "RUNTIME_MISSING"

        /** A measured value explains why the server may stop before it does. */
        const val NEEDS_ATTENTION: String = "NEEDS_ATTENTION"

        /** Something was not measured; the state is honestly unknown, not healthy. */
        const val PARTLY_UNKNOWN: String = "PARTLY_UNKNOWN"

        // ----------------------------------------------------- failure codes

        /** The platform refused a foreground-service start from the background. */
        const val FOREGROUND_START_NOT_ALLOWED: String = "FOREGROUND_START_NOT_ALLOWED"

        /** The service start was refused for a reason the engine could not name. */
        const val START_REFUSED: String = "START_REFUSED"

        /**
         * The projection of a Doctor report.
         *
         * The state and headline come from the `process` check first, because
         * "the server is not running" is the answer to the operator's actual
         * question; the rest of the report becomes advice.
         */
        fun of(report: DoctorReport): EmbedHealth {
            val counts = Counts(
                ok = report.count(DoctorLevel.OK),
                bad = report.count(DoctorLevel.BAD),
                notice = report.count(DoctorLevel.NOTICE),
                unknown = report.count(DoctorLevel.UNKNOWN)
            )
            val process = report.checks.firstOrNull { it.id == DoctorRules.CHECK_PROCESS }
            val (state, headline) = headlineOf(process, report)
            return EmbedHealth(
                state = state,
                headline = headline,
                advice = adviceOf(report),
                measuredAtMs = report.facts.measuredAtMs,
                counts = counts
            )
        }

        /**
         * The projection of a refused start.
         *
         * A refusal is not a Doctor measurement, so it does not pretend to carry
         * a report: the state is [EmbedState.STOPPED] and the advice is the one
         * action that can clear it.
         */
        fun ofFailure(code: String): EmbedHealth = EmbedHealth(
            state = EmbedState.STOPPED,
            headline = code,
            advice = listOf(EmbedAdvice(code, actionFor(code)))
        )

        private fun headlineOf(
            process: DoctorCheck?,
            report: DoctorReport
        ): Pair<EmbedState, String> = when {
            process == null -> EmbedState.UNKNOWN to PARTLY_UNKNOWN
            process.level == DoctorLevel.BAD -> when (process.code) {
                "PROCESS_QUARANTINED" -> EmbedState.STOPPED to SERVER_QUARANTINED
                "PROCESS_NO_RUNTIME" -> EmbedState.STOPPED to RUNTIME_MISSING
                "PROCESS_STOPPED_UNCLEAN" -> EmbedState.STOPPED to SERVER_DIED
                else -> EmbedState.STOPPED to SERVER_STOPPED
            }
            // A process that is still starting is only "starting" while
            // nothing else has failed; a measured fault outranks the spinner.
            process.level == DoctorLevel.NOTICE ->
                if (report.count(DoctorLevel.BAD) > 0) {
                    EmbedState.ATTENTION to NEEDS_ATTENTION
                } else {
                    EmbedState.STARTING to SERVER_STARTING
                }
            // No status at all is a measurement: the server is not running. An
            // unrecognised state is not — saying "stopped" there would be a
            // guess about a server we know exists, so it stays unknown.
            process.level == DoctorLevel.UNKNOWN && process.code == "PROCESS_UNKNOWN_STATE" ->
                EmbedState.UNKNOWN to PARTLY_UNKNOWN
            process.level == DoctorLevel.UNKNOWN ->
                EmbedState.STOPPED to SERVER_STOPPED
            report.verdict == DoctorVerdict.PASS -> EmbedState.RUNNING to SERVER_RUNNING
            report.count(DoctorLevel.BAD) > 0 -> EmbedState.ATTENTION to NEEDS_ATTENTION
            else -> EmbedState.UNKNOWN to PARTLY_UNKNOWN
        }

        /**
         * Every `BAD` check, in a fixed priority order, capped at [MAX_ADVICE].
         *
         * Only measurements that went wrong become advice. A `NOTICE` — the data
         * plane deliberately off, no SSH target saved — is a choice, not a fault,
         * and turning it into a to-do would nag the operator about a decision
         * they made.
         */
        private fun adviceOf(report: DoctorReport): List<EmbedAdvice> {
            val bad = report.checks.filter { it.level == DoctorLevel.BAD }
            if (bad.isEmpty()) return emptyList()
            val byCode = bad.mapNotNull { check ->
                check.code?.let { code -> EmbedAdvice(code, actionFor(code)) }
            }
            val ranked = byCode.sortedBy { advice ->
                val index = PRIORITY.indexOf(advice.code)
                if (index < 0) PRIORITY.size else index
            }
            return ranked.distinctBy { it.code }.take(MAX_ADVICE)
        }

        /**
         * What the operator can do about a code.
         *
         * `NONE` is a real answer: "the Node runtime is not packaged in this
         * build" has no settings page that fixes it, and offering a button that
         * opens an unrelated screen is worse than offering none. Codes that are
         * new to this table also fall through to `NONE` rather than to a guess.
         */
        fun actionFor(code: String): EmbedAction = when (code) {
            "BATTERY_OPTIMISED",
            "STANDBY_RARE",
            "STANDBY_RESTRICTED",
            "STANDBY_NEVER",
            "EXIT_LOW_MEMORY",
            "EXIT_SIGNALED",
            "EXIT_FREEZER",
            "EXIT_EXCESSIVE_RESOURCE_USAGE" -> EmbedAction.OPEN_BATTERY_SETTINGS

            "NOTIFICATIONS_OFF" -> EmbedAction.OPEN_NOTIFICATION_SETTINGS

            "BACKGROUND_RESTRICTED", "STORAGE_LOW" -> EmbedAction.OPEN_APP_SETTINGS

            "PROCESS_QUARANTINED",
            "PROCESS_STOPPED",
            "PROCESS_STOPPED_UNCLEAN",
            "AGENT_CRASHING",
            "DATA_PLANE_NOT_RUNNING",
            "EXIT_CRASH",
            "EXIT_CRASH_NATIVE",
            "EXIT_ANR",
            "EXIT_UNRECORDED" -> EmbedAction.RESTART_SERVER

            EmbedConsent.REQUIRED,
            EmbedConsent.OUTDATED -> EmbedAction.GRANT_CONSENT

            "PORT_IN_USE",
            "BAD_CONTROL_PORT",
            FOREGROUND_START_NOT_ALLOWED,
            START_REFUSED -> EmbedAction.RETRY

            "PORT_CHANGE_RESTART_REQUIRED" -> EmbedAction.RESTART_SERVER

            else -> EmbedAction.NONE
        }

        /** Most actionable first; anything unranked keeps the Doctor's own order at the end. */
        private val PRIORITY: List<String> = listOf(
            "BATTERY_OPTIMISED",
            "STANDBY_RESTRICTED",
            "STANDBY_NEVER",
            "STANDBY_RARE",
            "BACKGROUND_RESTRICTED",
            "NOTIFICATIONS_OFF",
            "PROCESS_QUARANTINED",
            "PROCESS_NO_RUNTIME",
            "PROCESS_STOPPED_UNCLEAN",
            "PROCESS_STOPPED",
            "AGENT_CRASHING",
            "DATA_PLANE_NOT_RUNNING",
            "EXIT_LOW_MEMORY",
            "EXIT_SIGNALED",
            "EXIT_ANR",
            "EXIT_CRASH",
            "EXIT_CRASH_NATIVE",
            "EXIT_EXCESSIVE_RESOURCE_USAGE",
            "EXIT_FREEZER",
            "EXIT_UNRECORDED",
            "STORAGE_LOW",
            "BRIDGE_NOT_CONNECTED"
        )
    }
}

/** The one word a host needs to colour its own screen. */
enum class EmbedState {
    /** Measured healthy: the only state a host may paint green. */
    RUNNING,

    /** Starting, or partly measured; neither healthy nor broken. */
    STARTING,

    /** Measured and not healthy, but the server may still be serving. */
    ATTENTION,

    /** Not running, or nothing could be measured. */
    STOPPED,

    /** Something was not measured; the host must not claim health. */
    UNKNOWN
}

/** What the operator can do; the host maps each to its own control. */
enum class EmbedAction {
    /** Nothing actionable from the app; say what it means and move on. */
    NONE,

    /** Show the consent notice, then call `recordConsent` and `start` again. */
    GRANT_CONSENT,

    /** Open Android's battery-optimisation screen for this app. */
    OPEN_BATTERY_SETTINGS,

    /** Open this app's notification settings (a hidden foreground service is fragile). */
    OPEN_NOTIFICATION_SETTINGS,

    /** Open this app's own settings page. */
    OPEN_APP_SETTINGS,

    /** Stop and start the embedded server. */
    RESTART_SERVER,

    /** Try the same call again — a port may have been freed, or the app is foreground now. */
    RETRY
}

/** A stable code and the action that addresses it. */
data class EmbedAdvice(val code: String, val action: EmbedAction)
