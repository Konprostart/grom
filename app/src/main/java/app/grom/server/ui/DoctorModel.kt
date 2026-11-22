// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R
import java.util.Locale

/**
 * The Doctor section (D80).
 *
 * Pure Kotlin: no `android.*`, no resources read, no formatting done by the
 * platform. It decides *which* rows exist, *which* lamp each one gets and *what*
 * value it shows; the activity decides how to say it. That split is what makes
 * "a lamp is only green for a measured value" testable on the JVM (D30), and the
 * engine's `DoctorRules` already made the decision — this file only maps it.
 *
 * The three levels the screen distinguishes, and the reason each exists:
 *
 *  - **measured** ([Lamp.ON] / [Lamp.OFF]) — Android returned a value;
 *  - **unknown** ([Lamp.UNKNOWN]) — this Android, version or build did not
 *    answer. It is not "fine", and the row says so in words;
 *  - **notice** ([Lamp.UNKNOWN] with a word) — a measured thing that is neither
 *    healthy nor a fault, such as "the public API is off".
 *
 * There is no fourth level that means "probably fine". [FactsRow] carries the
 * context lines ("Right now") that deliberately have **no** lamp, so a hot phone
 * or a flat battery is never dressed up as a diagnosis.
 */
data class DoctorRow(
    val id: String,
    val titleRes: Int,
    val lamp: Lamp,
    /** Measured value or level word; always non-empty. */
    val valueRes: Int,
    /** Stable code translated to a sentence; 0 when the level says it all. */
    val detailRes: Int = 0
)

data class FactsRow(
    val labelRes: Int,
    val valueText: String
)

object DoctorModel {

    /** Check ids, duplicated from the engine so this file stays pure. */
    const val ID_PROCESS: String = "process"
    const val ID_LAST_EXIT: String = "last_exit"
    const val ID_BATTERY: String = "battery"
    const val ID_BACKGROUND: String = "background"
    const val ID_STANDBY: String = "standby"
    const val ID_NOTIFICATIONS: String = "notifications"
    const val ID_AGENT: String = "agent"
    const val ID_DATA_PLANE: String = "data_plane"
    const val ID_BRIDGE: String = "bridge"
    const val ID_STORAGE: String = "storage"

    /** The render order, matching `DoctorRules.ORDER`. */
    val ORDER: List<String> = listOf(
        ID_PROCESS,
        ID_LAST_EXIT,
        ID_BATTERY,
        ID_BACKGROUND,
        ID_STANDBY,
        ID_NOTIFICATIONS,
        ID_AGENT,
        ID_DATA_PLANE,
        ID_BRIDGE,
        ID_STORAGE
    )

    fun titleRes(id: String): Int = when (id) {
        ID_PROCESS -> R.string.doctor_check_process
        ID_LAST_EXIT -> R.string.doctor_check_exit
        ID_BATTERY -> R.string.doctor_check_battery
        ID_BACKGROUND -> R.string.doctor_check_background
        ID_STANDBY -> R.string.doctor_check_standby
        ID_NOTIFICATIONS -> R.string.doctor_check_notifications
        ID_AGENT -> R.string.doctor_check_agent
        ID_DATA_PLANE -> R.string.doctor_check_data_plane
        ID_BRIDGE -> R.string.doctor_check_bridge
        ID_STORAGE -> R.string.doctor_check_storage
        else -> R.string.doctor_check_unknown
    }

    /** The headline, from the engine's verdict. Absent data has its own sentence. */
    fun verdictRes(verdict: String?): Int = when (verdict) {
        "PASS" -> R.string.doctor_verdict_pass
        "ATTENTION" -> R.string.doctor_verdict_attention
        "PARTLY_UNKNOWN" -> R.string.doctor_verdict_partly_unknown
        "NOTHING_MEASURED" -> R.string.doctor_verdict_nothing_measured
        else -> R.string.doctor_verdict_unavailable
    }

    fun verdictLamp(verdict: String?): Lamp = when (verdict) {
        "PASS" -> Lamp.ON
        "ATTENTION" -> Lamp.OFF
        // "Partly unknown" and "nothing measured" are grey: the screen refuses to
        // claim health it did not observe.
        else -> Lamp.UNKNOWN
    }

    fun levelLamp(level: String?): Lamp = when (level) {
        "OK" -> Lamp.ON
        "BAD" -> Lamp.OFF
        else -> Lamp.UNKNOWN
    }

    fun levelRes(level: String?): Int = when (level) {
        "OK" -> R.string.doctor_level_ok
        "BAD" -> R.string.doctor_level_bad
        "NOTICE" -> R.string.doctor_level_notice
        else -> R.string.doctor_level_unknown
    }

    /**
     * A stable code to a sentence. Unknown codes return 0 and the row simply
     * shows its level, so a code the engine adds later degrades to "needs
     * attention" instead of an empty line or a raw constant on screen.
     */
    fun codeRes(code: String?): Int = when (code) {
        "PROCESS_STOPPED" -> R.string.doctor_code_process_stopped
        "PROCESS_STOPPED_UNCLEAN" -> R.string.doctor_code_process_unclean
        "PROCESS_QUARANTINED" -> R.string.doctor_code_process_quarantined
        "PROCESS_NO_RUNTIME" -> R.string.doctor_code_process_no_runtime
        "PROCESS_STARTING" -> R.string.doctor_code_process_starting
        "PROCESS_NOT_RUNNING", "PROCESS_UNKNOWN_STATE" ->
            R.string.doctor_code_process_not_running
        "EXIT_LOW_MEMORY" -> R.string.doctor_code_exit_low_memory
        "EXIT_SIGNALED" -> R.string.doctor_code_exit_signaled
        "EXIT_ANR" -> R.string.doctor_code_exit_anr
        "EXIT_CRASH", "EXIT_CRASH_NATIVE" -> R.string.doctor_code_exit_crash
        "EXIT_EXCESSIVE_RESOURCE_USAGE" -> R.string.doctor_code_exit_excessive
        "EXIT_FREEZER" -> R.string.doctor_code_exit_freezer
        "EXIT_USER_STOPPED" -> R.string.doctor_code_exit_user_stopped
        "EXIT_EXIT_SELF" -> R.string.doctor_code_exit_self
        "EXIT_USER_REQUESTED" -> R.string.doctor_code_exit_user_requested
        "EXIT_PACKAGE_UPDATED" -> R.string.doctor_code_exit_updated
        "EXIT_UNRECORDED" -> R.string.doctor_code_exit_unrecorded
        "EXIT_NOT_AVAILABLE" -> R.string.doctor_code_exit_not_available
        "EXIT_NONE_RECORDED" -> R.string.doctor_code_exit_none
        "EXIT_OTHER", "EXIT_UNKNOWN" -> R.string.doctor_code_exit_other
        "BATTERY_OPTIMISED" -> R.string.doctor_code_battery_optimised
        "BATTERY_NOT_MEASURED" -> R.string.doctor_code_battery_unknown
        "BACKGROUND_RESTRICTED" -> R.string.doctor_code_background_restricted
        "BACKGROUND_NOT_MEASURED" -> R.string.doctor_code_background_unknown
        "STANDBY_NOT_MEASURED" -> R.string.doctor_code_standby_unknown
        "STANDBY_EXEMPTED" -> R.string.doctor_code_standby_exempted
        "STANDBY_ACTIVE" -> R.string.doctor_code_standby_active
        "STANDBY_WORKING_SET" -> R.string.doctor_code_standby_working_set
        "STANDBY_FREQUENT" -> R.string.doctor_code_standby_frequent
        "STANDBY_RARE" -> R.string.doctor_code_standby_rare
        "STANDBY_RESTRICTED" -> R.string.doctor_code_standby_restricted
        "STANDBY_NEVER" -> R.string.doctor_code_standby_never
        "STANDBY_UNRECOGNISED" -> R.string.doctor_code_standby_unknown
        "NOTIFICATIONS_OFF" -> R.string.doctor_code_notifications_off
        "NOTIFICATIONS_NOT_MEASURED" -> R.string.doctor_code_notifications_unknown
        "AGENT_CRASHING" -> R.string.doctor_code_agent_crashing
        "AGENT_RESTARTED" -> R.string.doctor_code_agent_restarted
        "AGENT_NOT_MEASURED" -> R.string.doctor_code_agent_unknown
        "DATA_PLANE_NOT_RUNNING" -> R.string.doctor_code_data_plane_stopped
        "DATA_PLANE_OFF" -> R.string.doctor_code_data_plane_off
        "DATA_PLANE_NOT_MEASURED" -> R.string.doctor_code_data_plane_unknown
        "BRIDGE_NOT_CONNECTED" -> R.string.doctor_code_bridge_down
        "BRIDGE_NONE" -> R.string.doctor_code_bridge_none
        "BRIDGE_NOT_MEASURED" -> R.string.doctor_code_bridge_unknown
        "STORAGE_LOW" -> R.string.doctor_code_storage_low
        "STORAGE_NOT_MEASURED" -> R.string.doctor_code_storage_unknown
        else -> 0
    }

    /**
     * The measured rows. A document without a `checks` array (the server is
     * stopped, or the Binder is not bound) yields the full list as unknown:
     * every row stays on screen, grey, because a missing section is not a
     * healthy one.
     */
    fun rows(document: Map<*, *>?): List<DoctorRow> {
        val encoded = (document?.get("checks") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.associateBy { it["id"] as? String }

        return ORDER.map { id ->
            val check = encoded?.get(id)
            val level = check?.get("level") as? String
            DoctorRow(
                id = id,
                titleRes = titleRes(id),
                lamp = levelLamp(level),
                valueRes = levelRes(level),
                detailRes = codeRes(check?.get("code") as? String)
            )
        }
    }

    /** How many rows need attention; 0 when nothing measured `BAD`. */
    fun attentionCount(document: Map<*, *>?): Int {
        val checks = document?.get("checks") as? List<*> ?: return 0
        return checks.count { (it as? Map<*, *>)?.get("level") == "BAD" }
    }

    fun unknownCount(document: Map<*, *>?): Int {
        val checks = document?.get("checks") as? List<*> ?: return 0
        return checks.count { (it as? Map<*, *>)?.get("level") == "UNKNOWN" }
    }

    /**
     * The "Right now" lines. No lamp and no verdict: these are context, and a
     * row here must never be read as a pass. A null value says "unknown" in
     * words rather than showing a dash, because a dash in this column has been
     * mistaken for zero before now.
     */
    fun facts(document: Map<*, *>?, unknown: String): List<FactsRow> {
        val facts = document?.get("facts") as? Map<*, *>
            ?: return emptyList()
        fun bool(key: String): String = when (facts[key] as? Boolean) {
            true -> "yes"
            false -> "no"
            else -> unknown
        }
        fun number(key: String): Long? = (facts[key] as? Number)?.toLong()
        fun flag(key: String): Boolean? = facts[key] as? Boolean

        val lines = mutableListOf<FactsRow>()
        lines += FactsRow(R.string.doctor_fact_doze, bool("deviceIdle"))
        lines += FactsRow(R.string.doctor_fact_power_saver, bool("powerSaver"))
        lines += FactsRow(
            R.string.doctor_fact_battery,
            batteryText(number("batteryPercent")?.toInt(), flag("charging"), unknown)
        )
        lines += FactsRow(
            R.string.doctor_fact_thermal,
            thermalText(number("thermalStatus")?.toInt(), unknown)
        )
        lines += FactsRow(R.string.doctor_fact_network, networkText(facts, unknown))
        lines += FactsRow(R.string.doctor_fact_storage, bytesText(number("freeBytes"), unknown))
        lines += FactsRow(
            R.string.doctor_fact_memory,
            memoryText(flag("lowMemory"), number("availableMemBytes"), unknown)
        )
        lines += FactsRow(R.string.doctor_fact_android, number("sdkInt")?.toString() ?: unknown)
        lines += FactsRow(R.string.doctor_fact_unexpected_stops, unexpectedText(facts, unknown))
        return lines
    }

    // -------------------------------------------------------------- formatting

    private fun batteryText(percent: Int?, charging: Boolean?, unknown: String): String =
        when {
            percent == null -> unknown
            charging == true -> "$percent% · charging"
            charging == false -> "$percent% · not charging"
            else -> "$percent%"
        }

    /** `PowerManager` thermal constants, named rather than numbered. */
    fun thermalText(status: Int?, unknown: String): String = when (status) {
        null -> unknown
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "status $status"
    }

    private fun networkText(facts: Map<*, *>, unknown: String): String {
        val kind = facts["network"] as? String ?: return unknown
        val metered = when (facts["metered"] as? Boolean) {
            true -> " · metered"
            false -> " · unmetered"
            else -> ""
        }
        return kind + metered
    }

    /** Binary units, one decimal, so "1.5 GB" is readable and stable. */
    fun bytesText(bytes: Long?, unknown: String): String {
        if (bytes == null) return unknown
        if (bytes < 0) return unknown
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            else -> String.format(Locale.US, "%.0f MB", mb)
        }
    }

    private fun memoryText(lowMemory: Boolean?, availableBytes: Long?, unknown: String): String {
        if (lowMemory == null && availableBytes == null) return unknown
        val free = bytesText(availableBytes, unknown)
        return when (lowMemory) {
            true -> "$free · low"
            false -> free
            else -> free
        }
    }

    private fun unexpectedText(facts: Map<*, *>, unknown: String): String {
        val count = (facts["unexpectedStops"] as? Number)?.toInt() ?: return unknown
        val last = (facts["lastUnexpectedStopMs"] as? Number)?.toLong() ?: 0L
        return if (last <= 0L) "$count" else "$count · last ${relativeAge(last, unknown)}"
    }

    /**
     * Deliberately coarse: the screen is not a stopwatch and a precise number
     * would suggest a precision the marker does not have.
     */
    fun relativeAge(thenMs: Long, unknown: String, nowMs: Long = System.currentTimeMillis()): String {
        if (thenMs <= 0L) return unknown
        val delta = nowMs - thenMs
        if (delta < 0L) return unknown
        val minutes = delta / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes min ago"
            hours < 24L -> "$hours h ago"
            else -> "$days d ago"
        }
    }
}
