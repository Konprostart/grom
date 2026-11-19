// SPDX-License-Identifier: Apache-2.0
package app.grom.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import app.grom.server.GromActivity
import app.grom.server.R
import app.grom.server.ServerRuntime
import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.sdk.ipc.IGromServer

/**
 * The server role's foreground service (D21, S4-R004…S4-R014).
 *
 * onBind applies the signature gate before handing out a Binder; a caller with
 * a different certificate gets `null` and never sees the interface.
 */
class GromService : Service() {

    private lateinit var runtime: ServerRuntime
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        runtime = ServerRuntime(applicationContext) { line -> Log.i(TAG, line) }
        startForegroundSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        return try {
            runtime.start()
            START_STICKY
        } catch (failure: Throwable) {
            Log.e(TAG, "server start refused: ${failure.javaClass.simpleName}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        val callingUid = Binder.getCallingUid()
        if (!SignatureGate.isSameSigner(packageManager, Process.myUid(), callingUid)) {
            Log.w(TAG, "rejected IPC bind from uid=$callingUid")
            return null
        }
        if (!runtime.isRunning()) {
            try {
                runtime.start()
            } catch (failure: Throwable) {
                Log.e(TAG, "server start refused during bind")
                return null
            }
        }
        return binder
    }

    override fun onDestroy() {
        runtime.stop()
        super.onDestroy()
    }

    private val binder: IGromServer.Stub = object : IGromServer.Stub() {
        override fun apiVersion(): Int = runtime.apiVersion()

        override fun healthJson(): String = Json.encode(runtime.health())

        override fun statusJson(): String = Json.encode(runtime.status())

        override fun auditTail(maxEntries: Int): String =
            Json.encode(runtime.auditTail(maxEntries.coerceIn(1, 200)))

        override fun restartAgent() = guard { runtime.restartAgent() }

        override fun resetAgent() = guard { runtime.resetAgent() }

        override fun rotateToken() = guard { runtime.rotateToken() }
    }

    /** Maps server failures to the stable client-visible exception (S4-R016). */
    private fun guard(block: () -> Unit) {
        try {
            block()
        } catch (failure: ApiException) {
            throw IllegalStateException("GROM_E_${failure.code}: ${failure.message}")
        }
    }

    private fun startForegroundSafely() {
        if (foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, GromActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    companion object {
        private const val TAG = "GromService"
        private const val CHANNEL_ID = "grom-server"
        private const val NOTIFICATION_ID = 47001
    }
}
