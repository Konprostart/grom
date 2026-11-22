// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

/**
 * Everything one Doctor measurement observed, with nothing interpreted (D80).
 *
 * Every field is nullable, and `null` means exactly one thing: *this Android did
 * not tell us*. It never means "no" and it never means "fine". That is what lets
 * [DoctorRules] produce a grey `unknown` instead of inventing an answer, which is
 * the whole point of the screen — a green lamp has to be backed by a value the
 * system returned.
 *
 * The class is pure Kotlin on purpose: [AndroidDoctorProbe] fills it in, but the
 * decisions all live in [DoctorRules], so they are testable on the JVM (D30).
 *
 * Facts are grouped the way the screen reads:
 *  - **policy** — settings the system applies to this package (battery, standby);
 *  - **now** — a snapshot of the device at the moment of the measurement;
 *  - **server** — what the engine already reports in its own status;
 *  - **exit** — why the previous process ended, when the platform says so (D83).
 */
data class DoctorFacts(

    // ---------------------------------------------------------------- policy

    /** `PowerManager.isIgnoringBatteryOptimizations`; already ignoring Doze. */
    val batteryExempt: Boolean? = null,
    /** `ActivityManager.isBackgroundRestricted`; the user-level switch. */
    val backgroundRestricted: Boolean? = null,
    /** `UsageStatsManager.getAppStandbyBucket`, raw bucket constant. */
    val standbyBucket: Int? = null,
    /** `PowerManager.isPowerSaveMode`; battery saver is on right now. */
    val powerSaver: Boolean? = null,
    /** `NotificationManager.areNotificationsEnabled`; a silent FGS is invisible. */
    val notificationsEnabled: Boolean? = null,

    // ------------------------------------------------------------------- now

    /** `PowerManager.isDeviceIdleMode`; the device is in Doze *at this instant*. */
    val deviceIdle: Boolean? = null,
    /** `PowerManager.getCurrentThermalStatus` (API 29+), raw enum. */
    val thermalStatus: Int? = null,
    /** Battery level 0..100 from the sticky `ACTION_BATTERY_CHANGED`. */
    val batteryPercent: Int? = null,
    /** True while the battery is charging or full. */
    val charging: Boolean? = null,
    /** `ActivityManager.isLowRamDevice`; Android Go kills background work harder. */
    val lowRamDevice: Boolean? = null,
    /** Free bytes on the volume that holds `filesDir`. */
    val freeBytes: Long? = null,
    /** `ActivityManager.MemoryInfo.availMem` in bytes. */
    val availableMemBytes: Long? = null,
    /** `ActivityManager.MemoryInfo.lowMemory`; the kernel is already reclaiming. */
    val lowMemory: Boolean? = null,
    /** `"wifi"`, `"mobile"`, `"ethernet"`, `"other"`, `"none"` or null if unknown. */
    val network: String? = null,
    /** `NetworkCapabilities.NOT_METERED` is absent; null when there is no network. */
    val metered: Boolean? = null,

    // ---------------------------------------------------------------- server

    /** `READY`, `STARTING`, `STOPPED`, `QUARANTINED`, `MISSING_RUNTIME` or null. */
    val serverState: String? = null,
    /** `SupervisorPolicy` crash count for the current run. */
    val crashes: Int? = null,
    /** `SupervisorPolicy` restart count, so a restart loop is visible. */
    val restarts: Int? = null,
    /** Public data plane: configured at all. */
    val dataPlaneEnabled: Boolean? = null,
    /** Public data plane: measured listening right now. */
    val dataPlaneRunning: Boolean? = null,
    /** At least one SSH target is saved. */
    val bridgeConfigured: Boolean? = null,
    /** At least one SSH tunnel is up right now. */
    val bridgeConnected: Boolean? = null,
    /** How many tunnels are up; distinguishes "one" from "some". */
    val tunnelsConnected: Int? = null,

    // ------------------------------------------------------------------ exit

    /**
     * When this process noticed that the previous one ended **without**
     * `onDestroy` (D83). Detection time, not a fabricated death time: the marker
     * cannot know the exact moment, and inventing one would be a guess.
     */
    val lastUnexpectedStopMs: Long? = null,
    /** How many starts have seen an unclean predecessor since the marker began. */
    val unexpectedStops: Int? = null,
    /** When the previous session started, so the death window is at least bounded. */
    val previousSessionStartedAtMs: Long? = null,
    /** `ApplicationExitInfo` reason name (API 30+), already mapped to a stable string. */
    val lastExitReason: String? = null,
    /** When the last recorded exit happened, per the platform (API 30+). */
    val lastExitAtMs: Long? = null,

    /** `Build.VERSION.SDK_INT`; decides whether a probe could exist at all. */
    val sdkInt: Int = 0,

    /** Wall clock of the measurement, for the "measured at" line. */
    val measuredAtMs: Long = 0L
)
