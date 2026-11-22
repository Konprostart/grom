// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

/**
 * One measured line of the Doctor screen (D80).
 *
 * `id` is a stable key the app maps to a localized title; `code` is a stable,
 * machine-readable qualifier the app maps to a localized detail. Neither is
 * free text, so a vendor's exception message can never end up on the screen and
 * the set of possible sentences is fixed at build time.
 */
data class DoctorCheck(
    val id: String,
    val level: DoctorLevel,
    /** Stable qualifier such as `EXIT_LOW_MEMORY`; null when the level says it all. */
    val code: String? = null
)

/** The four outcomes a check can have. Only [OK] and [BAD] are measurements. */
enum class DoctorLevel {
    /** The system returned a value and it is the healthy one. */
    OK,

    /** The system returned a value and it explains why the server may stop. */
    BAD,

    /** The system returned a value that is neither healthy nor a fault. */
    NOTICE,

    /** Nothing was measured: no API, no permission, or the server was not running. */
    UNKNOWN
}

/** The headline. It is never [PASS] unless every check actually measured `OK`. */
enum class DoctorVerdict { PASS, ATTENTION, PARTLY_UNKNOWN, NOTHING_MEASURED }

/** The whole report: the verdict, the checks and the facts behind them. */
data class DoctorReport(
    val verdict: DoctorVerdict,
    val checks: List<DoctorCheck>,
    val facts: DoctorFacts
) {
    fun count(level: DoctorLevel): Int = checks.count { it.level == level }
}

/**
 * The decisions of the Doctor screen, as one pure function (D80, D30).
 *
 * Three rules are absolute, and each one is asserted by `DoctorRulesTest`:
 *
 *  1. **A lamp is green only for a value the system returned.** A configured
 *     SSH target is not a connected tunnel, `enabled = true` is not a running
 *     data plane, and a saved pairing is not a live device. Configuration alone
 *     can only ever produce [DoctorLevel.UNKNOWN] or [DoctorLevel.BAD].
 *  2. **Missing data is `UNKNOWN`, never `OK`.** A field that is null produces a
 *     grey line: "this Android did not tell us", which is a different sentence
 *     from "this is fine".
 *  3. **The verdict cannot be green while anything is unknown.** A green
 *     headline that hides an unmeasured row is exactly the lie this screen
 *     exists to avoid (D62, D80).
 *
 * Thresholds are named constants so the app, the docs and the tests agree, and
 * so "why is it red" has one answer.
 */
object DoctorRules {

    // ------------------------------------------------------------------ ids

    const val CHECK_PROCESS: String = "process"
    const val CHECK_BATTERY: String = "battery"
    const val CHECK_BACKGROUND: String = "background"
    const val CHECK_STANDBY: String = "standby"
    const val CHECK_NOTIFICATIONS: String = "notifications"
    const val CHECK_AGENT: String = "agent"
    const val CHECK_DATA_PLANE: String = "data_plane"
    const val CHECK_BRIDGE: String = "bridge"
    const val CHECK_LAST_EXIT: String = "last_exit"
    const val CHECK_STORAGE: String = "storage"

    /** The order the screen renders, from "is it alive" to "can it keep working". */
    val ORDER: List<String> = listOf(
        CHECK_PROCESS,
        CHECK_LAST_EXIT,
        CHECK_BATTERY,
        CHECK_BACKGROUND,
        CHECK_STANDBY,
        CHECK_NOTIFICATIONS,
        CHECK_AGENT,
        CHECK_DATA_PLANE,
        CHECK_BRIDGE,
        CHECK_STORAGE
    )

    // ----------------------------------------------------------- thresholds

    /**
     * Below this, the Node runtime and the audit log have no room to grow and
     * the write path starts failing. The same 500 MB phonenode's `doctor` uses,
     * chosen because it is comfortably larger than a runtime install.
     */
    const val MIN_FREE_BYTES: Long = 500L * 1024L * 1024L

    /** Crashes in one run that mean "this is a pattern, not bad luck". */
    const val CRASH_WARN: Int = 1
    const val CRASH_BAD: Int = 3

    /** `ApplicationExitInfo` reasons that are a clean end of the process. */
    val CLEAN_EXIT_REASONS: Set<String> = setOf(
        "USER_STOPPED",
        "EXIT_SELF",
        "USER_REQUESTED",
        "PACKAGE_UPDATED"
    )

    /** Exit reasons that say the platform or the kernel took the process away. */
    val FORCED_EXIT_REASONS: Set<String> = setOf(
        "LOW_MEMORY",
        "SIGNALED",
        "ANR",
        "CRASH",
        "CRASH_NATIVE",
        "EXCESSIVE_RESOURCE_USAGE",
        "FREEZER"
    )

    // ------------------------------------------------------------- evaluation

    fun evaluate(facts: DoctorFacts): DoctorReport {
        val checks = listOf(
            process(facts),
            lastExit(facts),
            battery(facts),
            background(facts),
            standby(facts),
            notifications(facts),
            agent(facts),
            dataPlane(facts),
            bridge(facts),
            storage(facts)
        )
        return DoctorReport(
            verdict = verdictOf(checks),
            checks = checks,
            facts = facts
        )
    }

    /**
     * Only a report where nothing is unknown and nothing is bad is a pass. A
     * report with no measured check at all is not a pass either: it said nothing.
     */
    fun verdictOf(checks: List<DoctorCheck>): DoctorVerdict {
        val measured = checks.count { it.level != DoctorLevel.UNKNOWN }
        if (measured == 0) return DoctorVerdict.NOTHING_MEASURED
        if (checks.any { it.level == DoctorLevel.BAD }) return DoctorVerdict.ATTENTION
        val unknown = checks.count { it.level == DoctorLevel.UNKNOWN }
        if (unknown > 0) return DoctorVerdict.PARTLY_UNKNOWN
        return DoctorVerdict.PASS
    }

    // ------------------------------------------------------------ each check

    /**
     * The server process itself. `serverState` comes from the engine's own
     * status when the Binder is bound; with no Binder we still know whether the
     * last run ended cleanly, because the marker says so.
     */
    private fun process(facts: DoctorFacts): DoctorCheck {
        val state = facts.serverState
        if (state == null) {
            // No status at all: if the marker saw an unclean end, that is a real
            // measurement and it is bad. Otherwise nothing was measured.
            return if ((facts.unexpectedStops ?: 0) > 0) {
                DoctorCheck(CHECK_PROCESS, DoctorLevel.BAD, "PROCESS_STOPPED_UNCLEAN")
            } else {
                DoctorCheck(CHECK_PROCESS, DoctorLevel.UNKNOWN, "PROCESS_NOT_RUNNING")
            }
        }
        return when (state) {
            "READY" -> DoctorCheck(CHECK_PROCESS, DoctorLevel.OK)
            "STARTING" -> DoctorCheck(CHECK_PROCESS, DoctorLevel.NOTICE, "PROCESS_STARTING")
            "QUARANTINED" -> DoctorCheck(CHECK_PROCESS, DoctorLevel.BAD, "PROCESS_QUARANTINED")
            "MISSING_RUNTIME" -> DoctorCheck(CHECK_PROCESS, DoctorLevel.BAD, "PROCESS_NO_RUNTIME")
            "STOPPED" -> DoctorCheck(CHECK_PROCESS, DoctorLevel.BAD, "PROCESS_STOPPED")
            else -> DoctorCheck(CHECK_PROCESS, DoctorLevel.UNKNOWN, "PROCESS_UNKNOWN_STATE")
        }
    }

    /**
     * The previous exit (D83). The platform's own reason is used when it exists;
     * on API 29 there is no `ApplicationExitInfo`, and the marker's detection is
     * the only fact — so the row is `UNKNOWN` rather than a guess about Doze.
     */
    private fun lastExit(facts: DoctorFacts): DoctorCheck {
        val reason = facts.lastExitReason
        if (reason != null) {
            return when (reason) {
                in CLEAN_EXIT_REASONS -> DoctorCheck(CHECK_LAST_EXIT, DoctorLevel.OK, "EXIT_$reason")
                in FORCED_EXIT_REASONS -> DoctorCheck(CHECK_LAST_EXIT, DoctorLevel.BAD, "EXIT_$reason")
                else -> DoctorCheck(CHECK_LAST_EXIT, DoctorLevel.NOTICE, "EXIT_$reason")
            }
        }
        // No platform reason. The marker can still prove an unclean end happened.
        val unexpected = facts.lastUnexpectedStopMs
        if (unexpected != null && unexpected > 0L) {
            return DoctorCheck(CHECK_LAST_EXIT, DoctorLevel.BAD, "EXIT_UNRECORDED")
        }
        return if (facts.sdkInt in 1 until EXIT_INFO_MIN_SDK) {
            DoctorCheck(CHECK_LAST_EXIT, DoctorLevel.UNKNOWN, "EXIT_NOT_AVAILABLE")
        } else {
            DoctorCheck(CHECK_LAST_EXIT, DoctorLevel.UNKNOWN, "EXIT_NONE_RECORDED")
        }
    }

    private fun battery(facts: DoctorFacts): DoctorCheck {
        val exempt = facts.batteryExempt
            ?: return DoctorCheck(CHECK_BATTERY, DoctorLevel.UNKNOWN, "BATTERY_NOT_MEASURED")
        return if (exempt) {
            DoctorCheck(CHECK_BATTERY, DoctorLevel.OK)
        } else {
            DoctorCheck(CHECK_BATTERY, DoctorLevel.BAD, "BATTERY_OPTIMISED")
        }
    }

    private fun background(facts: DoctorFacts): DoctorCheck {
        val restricted = facts.backgroundRestricted
            ?: return DoctorCheck(CHECK_BACKGROUND, DoctorLevel.UNKNOWN, "BACKGROUND_NOT_MEASURED")
        return if (restricted) {
            DoctorCheck(CHECK_BACKGROUND, DoctorLevel.BAD, "BACKGROUND_RESTRICTED")
        } else {
            DoctorCheck(CHECK_BACKGROUND, DoctorLevel.OK)
        }
    }

    private fun standby(facts: DoctorFacts): DoctorCheck {
        val bucket = facts.standbyBucket
            ?: return DoctorCheck(CHECK_STANDBY, DoctorLevel.UNKNOWN, "STANDBY_NOT_MEASURED")
        return when {
            bucket in STANDBY_OK -> DoctorCheck(CHECK_STANDBY, DoctorLevel.OK, standbyName(bucket))
            bucket in STANDBY_RARE -> DoctorCheck(CHECK_STANDBY, DoctorLevel.BAD, standbyName(bucket))
            else -> DoctorCheck(CHECK_STANDBY, DoctorLevel.NOTICE, standbyName(bucket))
        }
    }

    private fun notifications(facts: DoctorFacts): DoctorCheck {
        val enabled = facts.notificationsEnabled
            ?: return DoctorCheck(
                CHECK_NOTIFICATIONS,
                DoctorLevel.UNKNOWN,
                "NOTIFICATIONS_NOT_MEASURED"
            )
        return if (enabled) {
            DoctorCheck(CHECK_NOTIFICATIONS, DoctorLevel.OK)
        } else {
            // A hidden foreground service is easier for a vendor to kill and
            // harder for the operator to notice.
            DoctorCheck(CHECK_NOTIFICATIONS, DoctorLevel.BAD, "NOTIFICATIONS_OFF")
        }
    }

    private fun agent(facts: DoctorFacts): DoctorCheck {
        val crashes = facts.crashes
            ?: return DoctorCheck(CHECK_AGENT, DoctorLevel.UNKNOWN, "AGENT_NOT_MEASURED")
        return when {
            crashes >= CRASH_BAD -> DoctorCheck(CHECK_AGENT, DoctorLevel.BAD, "AGENT_CRASHING")
            crashes >= CRASH_WARN -> DoctorCheck(CHECK_AGENT, DoctorLevel.NOTICE, "AGENT_RESTARTED")
            else -> DoctorCheck(CHECK_AGENT, DoctorLevel.OK)
        }
    }

    private fun dataPlane(facts: DoctorFacts): DoctorCheck {
        val enabled = facts.dataPlaneEnabled
        val running = facts.dataPlaneRunning
        if (enabled == null && running == null) {
            return DoctorCheck(CHECK_DATA_PLANE, DoctorLevel.UNKNOWN, "DATA_PLANE_NOT_MEASURED")
        }
        // Running is a measurement; enabled is configuration and cannot be green.
        if (running == true) return DoctorCheck(CHECK_DATA_PLANE, DoctorLevel.OK)
        return if (enabled == true) {
            DoctorCheck(CHECK_DATA_PLANE, DoctorLevel.BAD, "DATA_PLANE_NOT_RUNNING")
        } else {
            DoctorCheck(CHECK_DATA_PLANE, DoctorLevel.NOTICE, "DATA_PLANE_OFF")
        }
    }

    private fun bridge(facts: DoctorFacts): DoctorCheck {
        val configured = facts.bridgeConfigured
        val connected = facts.bridgeConnected
        if (configured == null && connected == null) {
            return DoctorCheck(CHECK_BRIDGE, DoctorLevel.UNKNOWN, "BRIDGE_NOT_MEASURED")
        }
        if (connected == true) return DoctorCheck(CHECK_BRIDGE, DoctorLevel.OK)
        return if (configured == true) {
            DoctorCheck(CHECK_BRIDGE, DoctorLevel.BAD, "BRIDGE_NOT_CONNECTED")
        } else {
            DoctorCheck(CHECK_BRIDGE, DoctorLevel.NOTICE, "BRIDGE_NONE")
        }
    }

    private fun storage(facts: DoctorFacts): DoctorCheck {
        val free = facts.freeBytes
            ?: return DoctorCheck(CHECK_STORAGE, DoctorLevel.UNKNOWN, "STORAGE_NOT_MEASURED")
        return if (free >= MIN_FREE_BYTES) {
            DoctorCheck(CHECK_STORAGE, DoctorLevel.OK)
        } else {
            DoctorCheck(CHECK_STORAGE, DoctorLevel.BAD, "STORAGE_LOW")
        }
    }

    // ------------------------------------------------------------ vocabulary

    /** `UsageStatsManager` bucket constants, duplicated so this file stays pure. */
    const val BUCKET_EXEMPTED: Int = 5
    const val BUCKET_ACTIVE: Int = 10
    const val BUCKET_WORKING_SET: Int = 20
    const val BUCKET_FREQUENT: Int = 30
    const val BUCKET_RARE: Int = 40
    const val BUCKET_RESTRICTED: Int = 45
    const val BUCKET_NEVER: Int = 50

    /** `ApplicationExitInfo` exists from API 30; below that the row says so. */
    const val EXIT_INFO_MIN_SDK: Int = 30

    private val STANDBY_OK = setOf(BUCKET_EXEMPTED, BUCKET_ACTIVE, BUCKET_WORKING_SET)
    private val STANDBY_RARE = setOf(BUCKET_RARE, BUCKET_RESTRICTED, BUCKET_NEVER)

    fun standbyName(bucket: Int): String = when (bucket) {
        BUCKET_EXEMPTED -> "STANDBY_EXEMPTED"
        BUCKET_ACTIVE -> "STANDBY_ACTIVE"
        BUCKET_WORKING_SET -> "STANDBY_WORKING_SET"
        BUCKET_FREQUENT -> "STANDBY_FREQUENT"
        BUCKET_RARE -> "STANDBY_RARE"
        BUCKET_RESTRICTED -> "STANDBY_RESTRICTED"
        BUCKET_NEVER -> "STANDBY_NEVER"
        else -> "STANDBY_UNRECOGNISED"
    }
}
