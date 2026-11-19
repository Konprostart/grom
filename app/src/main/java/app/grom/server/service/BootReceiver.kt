// SPDX-License-Identifier: Apache-2.0
package app.grom.server.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the server after boot (D14, S3-R025…S3-R028).
 *
 * Starts only when the user opted in. Quarantine is honoured by the service
 * itself, which refuses to start a quarantined agent, so a reboot can never
 * clear a quarantine.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        if (!GromPrefs.startOnBoot(context)) {
            Log.i(TAG, "boot: start-on-boot disabled")
            return
        }
        val service = Intent(context, GromService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
            Log.i(TAG, "boot: server start requested")
        } catch (failure: Exception) {
            Log.w(TAG, "boot: start refused by platform")
        }
    }

    companion object {
        private const val TAG = "GromBoot"
    }
}
