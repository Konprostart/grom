// SPDX-License-Identifier: Apache-2.0
package app.grom.server.doctor

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import java.io.File

/**
 * The only file in this package that reads the platform (D80, D81).
 *
 * It answers questions and makes no decisions: every method returns the value
 * the system gave, or `null` when the API does not exist on this Android version,
 * the call is not permitted, or it threw. `DoctorRules` turns those nulls into
 * grey `unknown` rows. Keeping the split strict is what makes the rules
 * JVM-testable and this file small enough to review in one sitting; `tools/
 * test-policy` asserts that it is the only file here with an `android.` import.
 *
 * Nothing here writes to the system: no settings are changed, no shell is run,
 * no permission is requested. The screen offers the operator a button that opens
 * the platform's own settings page, and that is the whole extent of it (D80).
 */
class AndroidDoctorProbe(
    private val context: Context,
    /** Injected so a test host can keep the measurement deterministic. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val logSink: (String) -> Unit = {}
) {

    fun facts(): DoctorFacts {
        val power = service(PowerManager::class.java)
        val activity = service(ActivityManager::class.java)
        val battery = battery()
        // One call, read once: two calls could straddle a new exit record and
        // report a reason that does not belong to the timestamp next to it.
        val exit = lastExit()
        // Same rule for memory: `availMem` and `lowMemory` come from one
        // `ActivityManager.MemoryInfo`, so a second read could pair a fresh
        // figure with the previous pressure flag.
        val memory = memory()

        return DoctorFacts(
            batteryExempt = power?.let {
                attempt("batteryExempt") { it.isIgnoringBatteryOptimizations(context.packageName) }
            },
            backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activity?.let { attempt("backgroundRestricted") { it.isBackgroundRestricted } }
            } else {
                null
            },
            standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service(UsageStatsManager::class.java)
                    ?.let { attempt("standbyBucket") { it.appStandbyBucket } }
            } else {
                null
            },
            powerSaver = power?.let { attempt("powerSaver") { it.isPowerSaveMode } },
            notificationsEnabled = service(NotificationManager::class.java)
                ?.let { attempt("notificationsEnabled") { it.areNotificationsEnabled() } },
            deviceIdle = power?.let { attempt("deviceIdle") { it.isDeviceIdleMode } },
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                power?.let { attempt("thermalStatus") { it.currentThermalStatus } }
            } else {
                null
            },
            batteryPercent = battery?.first,
            charging = battery?.second,
            lowRamDevice = activity?.let { attempt("lowRamDevice") { it.isLowRamDevice } },
            freeBytes = freeBytes(),
            availableMemBytes = memory?.first,
            lowMemory = memory?.second,
            network = network()?.first,
            metered = network()?.second,
            lastExitReason = exit?.first,
            lastExitAtMs = exit?.second,
            sdkInt = Build.VERSION.SDK_INT,
            measuredAtMs = nowMs()
        )
    }

    /**
     * The three fields `/v1/status` may carry (D84), and nothing else.
     *
     * It is a separate, cheap read on purpose: a hub asking for status every few
     * seconds must not pay for the exit history or the battery broadcast, so this
     * path touches two binder calls and the marker only.
     */
    fun policyFacts(): DoctorFacts {
        val power = service(PowerManager::class.java)
        val activity = service(ActivityManager::class.java)
        return DoctorFacts(
            batteryExempt = power?.let {
                attempt("batteryExempt") { it.isIgnoringBatteryOptimizations(context.packageName) }
            },
            backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activity?.let { attempt("backgroundRestricted") { it.isBackgroundRestricted } }
            } else {
                null
            },
            sdkInt = Build.VERSION.SDK_INT,
            measuredAtMs = nowMs()
        )
    }

    /**
     * The most recent process exit the platform recorded for this package.
     * API 30+ only; below that `ApplicationExitInfo` does not exist and the
     * answer is honestly `null` (D83).
     */
    fun lastExit(): Pair<String, Long>? {
        if (Build.VERSION.SDK_INT < DoctorRules.EXIT_INFO_MIN_SDK) return null
        val activity = service(ActivityManager::class.java) ?: return null
        return attempt("lastExit") {
            val reasons = activity.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            val info = reasons.firstOrNull() ?: return@attempt null
            exitName(info.reason) to info.timestamp
        }
    }

    // ------------------------------------------------------------------ probes

    /** Sticky broadcast: the battery is device-wide, so it needs no receiver. */
    private fun battery(): Pair<Int?, Boolean?>? {
        val intent = attempt("batteryIntent") {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            null
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    private fun freeBytes(): Long? {
        val dir: File = attempt("filesDir") { context.filesDir } ?: return null
        return attempt("freeBytes") {
            val stats = StatFs(dir.absolutePath)
            stats.availableBytes
        }
    }

    private fun memory(): Pair<Long?, Boolean?>? {
        val activity = service(ActivityManager::class.java) ?: return null
        return attempt("memory") {
            val info = ActivityManager.MemoryInfo()
            activity.getMemoryInfo(info)
            info.availMem to info.lowMemory
        }
    }

    private fun network(): Pair<String, Boolean?>? {
        val connectivity = service(ConnectivityManager::class.java) ?: return null
        return attempt("network") {
            val active = connectivity.activeNetwork
                ?: return@attempt "none" to null
            val caps = connectivity.getNetworkCapabilities(active)
                ?: return@attempt "none" to null
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            kind to !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }

    // ----------------------------------------------------------------- helpers

    /** `ApplicationExitInfo` reason names, mapped to the stable strings in rules. */
    private fun exitName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN"
    }

    private fun <T> service(type: Class<T>): T? = attempt(type.simpleName) {
        context.getSystemService(type)
    }

    /**
     * One place for "the platform said no". A vendor build that throws here must
     * not take the screen down with it, so the failure is logged by class name
     * and the field stays null — which is exactly `unknown`.
     */
    private fun <T> attempt(label: String, block: () -> T?): T? = try {
        block()
    } catch (failure: Throwable) {
        logSink("doctor probe $label failed: ${failure.javaClass.simpleName}")
        Log.d(TAG, "probe $label failed: ${failure.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "GromDoctor"
    }
}
