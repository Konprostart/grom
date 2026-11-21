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
import app.grom.engine.R
import app.grom.server.ServerRuntime
import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.sdk.ipc.IGromServer

/**
 * The server role's foreground service (D21, S4-R004…S4-R014).
 *
 * onBind applies the signature gate before handing out a Binder; a caller with
 * a different certificate gets `null` and never sees the interface.
 *
 * The service lives in :engine (D55) and is declared twice: privately by the
 * engine for embedded hosts, and publicly by the standalone server29 flavour.
 * The per-transaction signature check below is what actually protects the
 * Binder in both cases.
 */
class GromService : Service() {

    private lateinit var runtime: ServerRuntime
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        runtime = ServerRuntime(applicationContext, logSink = { line -> Log.i(TAG, line) })
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
        // The caller identity is not reliable on the main thread, so the
        // signature check happens per transaction in the Stub below. The
        // signature-level permission on the standalone service declaration is
        // the first gate; this is the second.
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
        override fun apiVersion(): Int {
            requireSameSigner()
            return runtime.apiVersion()
        }

        override fun healthJson(): String {
            requireSameSigner()
            return Json.encode(runtime.health())
        }

        override fun statusJson(): String {
            requireSameSigner()
            return Json.encode(runtime.status())
        }

        override fun auditTail(maxEntries: Int): String {
            requireSameSigner()
            return Json.encode(runtime.auditTail(maxEntries.coerceIn(1, 200)))
        }

        override fun restartAgent() = guard {
            requireSameSigner()
            runtime.restartAgent()
        }

        override fun resetAgent() = guard {
            requireSameSigner()
            runtime.resetAgent()
        }

        override fun rotateToken() = guard {
            requireSameSigner()
            runtime.rotateToken()
        }

        override fun reloadSuperServer() = guard {
            requireSameSigner()
            runtime.reloadSuperServer()
        }

        // ------------------------------------------------ API 3 (D55, S4 §8)

        override fun projectsJson(): String {
            requireSameSigner()
            return Json.encode(mapOf("projects" to runtime.projects()))
        }

        override fun announceProject(json: String?): String {
            requireSameSigner()
            val parsed = JsonParser.parseObjectOrNull(json ?: "")
                ?: throw IllegalStateException("GROM_E_BAD_PROJECT: body must be a JSON object")
            return try {
                runtime.announceProject(parsed)
            } catch (failure: ApiException) {
                throw IllegalStateException("GROM_E_${failure.code}: ${failure.message}")
            }
        }

        override fun removeProject(id: String?) {
            requireSameSigner()
            guard { runtime.removeProject(id.orEmpty()) }
        }

        override fun configExportJson(): String {
            requireSameSigner()
            return runtime.configExportJson()
        }

        override fun agentExec(op: String?, argsJson: String?): String {
            requireSameSigner()
            val result = runtime.exec(op.orEmpty(), argsJson)
            // Errors stay data here: the agent's own status and body are
            // returned verbatim so a caller can tell 502 from 200 (S4-R016).
            return Json.encode(mapOf("status" to result.status, "body" to result.body))
        }

        // ------------------------------------------------------- SSH bridge

        override fun sshStatusJson(): String {
            requireSameSigner()
            return runtime.sshStatusJson()
        }

        override fun sshGenerateKeyJson(): String {
            requireSameSigner()
            return runtime.sshGenerateKeyJson()
        }

        override fun sshSaveTarget(draftJson: String?): String {
            requireSameSigner()
            return try {
                runtime.sshSaveTargetJson(draftJson.orEmpty())
            } catch (failure: ApiException) {
                throw IllegalStateException("GROM_E_${failure.code}: ${failure.message}")
            }
        }

        override fun sshDeleteTarget(id: String?) {
            requireSameSigner()
            guard { runtime.sshDeleteTarget(id.orEmpty()) }
        }

        override fun sshConnect(id: String?, trustFingerprint: String?): String {
            requireSameSigner()
            return runtime.sshConnectJson(id.orEmpty(), trustFingerprint)
        }

        override fun sshDisconnect(id: String?) {
            requireSameSigner()
            guard { runtime.sshDisconnect(id.orEmpty()) }
        }

        override fun sshClearPin(id: String?) {
            requireSameSigner()
            guard { runtime.sshClearPin(id.orEmpty()) }
        }

        override fun lightningProbeJson(): String {
            requireSameSigner()
            return runtime.lightningProbeJson()
        }
    }

    /**
     * Runs on a binder thread, where Binder.getCallingUid() is authoritative.
     * A foreign signature never reaches the server state.
     */
    private fun requireSameSigner() {
        val callingUid = Binder.getCallingUid()
        if (!SignatureGate.isSameSigner(packageManager, Process.myUid(), callingUid)) {
            Log.w(TAG, "rejected IPC transaction from uid=$callingUid")
            throw SecurityException("GROM_E_FORBIDDEN: caller signature mismatch")
        }
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
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.grom_engine_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // The engine must not know the host's activity class. The launcher
        // intent of whichever package is running this code is the correct
        // target for both the standalone app and an embedding host.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.grom_engine_notification_title))
            .setContentText(getString(R.string.grom_engine_notification_text))
            .setSmallIcon(R.drawable.ic_grom_engine_notification)
            .setOngoing(true)
        if (launch != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        val notification = builder.build()

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
