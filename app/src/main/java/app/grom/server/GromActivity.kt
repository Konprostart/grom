// SPDX-License-Identifier: Apache-2.0
package app.grom.server

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.grom.sdk.GromClient

/**
 * Operator surface. Everything here is optional: the server is fully usable
 * over the HTTP API and the IPC surface without this screen.
 *
 * The activity dogfoods the public SDK client rather than reaching into the
 * service directly, so a regression in the SDK shows up in the app itself.
 */
class GromActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var client: GromClient
    private lateinit var statusView: TextView
    private var connected = false

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = GromClient(this)
        setContentView(buildUi())
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        connected = client.bind(object : GromClient.Listener {
            override fun onConnected(apiVersion: Int) {
                connected = true
                render()
            }

            override fun onDisconnected(reason: String) {
                connected = false
                render(reason)
            }
        })
        handler.post(refresher)
    }

    override fun onStop() {
        handler.removeCallbacks(refresher)
        client.unbind()
        connected = false
        super.onStop()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusView = TextView(this).apply {
            textSize = 13f
            text = "starting\u2026"
        }
        root.addView(statusView)

        if (BuildConfig.GROMSERVER) {
            root.addView(button("Start server") { startServer() })
            root.addView(button("Stop server") { stopServer() })
            root.addView(button("Restart agent") { invoke("restart") { it.restartAgent() } })
            root.addView(button("Reset quarantine") { invoke("reset") { it.resetAgent() } })
            root.addView(button("Rotate token") {
                invoke("rotate") { it.rotateToken() }
                Toast.makeText(this, "Token rotated. New value is in logcat-free storage.", Toast.LENGTH_LONG).show()
            })
            root.addView(button("Start-on-boot: ${if (GromPrefsHolder.startOnBoot(this)) "on" else "off"}") {
                GromPrefsHolder.toggleBoot(this)
                recreate()
            })
        } else {
            root.addView(TextView(this).apply {
                text = "Peripheral build: this role never binds a socket."
            })
        }

        return ScrollView(this).apply { addView(root) }
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun render(disconnectReason: String? = null) {
        if (!connected) {
            statusView.text = buildString {
                append("build: ").append(BuildConfig.VERSION_NAME).append('\n')
                append("role: ").append(if (BuildConfig.GROMSERVER) "server" else "peripheral").append('\n')
                append("sdk: ").append(Build.VERSION.SDK_INT).append('\n')
                append("ipc: not bound")
                if (disconnectReason != null) append(" \u2014 ").append(disconnectReason)
            }
            return
        }
        val statusJson = client.call("{}") { it.statusJson() }
        statusView.text = buildString {
            append("build: ").append(BuildConfig.VERSION_NAME).append('\n')
            append("role: ").append(if (BuildConfig.GROMSERVER) "server" else "peripheral").append('\n')
            append("ipc: bound, api=").append(client.call(-1) { it.apiVersion() }).append('\n')
            append('\n')
            append(statusJson)
        }
    }

    private fun invoke(label: String, block: (app.grom.sdk.ipc.IGromServer) -> Unit) {
        val server = client.server
        if (server == null) {
            toast("$label: not bound")
            return
        }
        try {
            block(server)
            toast("$label: ok")
        } catch (failure: IllegalStateException) {
            toast("$label: ${failure.message}")
        } catch (failure: android.os.RemoteException) {
            toast("$label: server went away")
        }
        render()
    }

    private fun startServer() {
        // minSdk is 26 for both flavours, so startForegroundService is safe.
        startForegroundService(Intent(this, app.grom.server.service.GromService::class.java))
        handler.postDelayed({ render() }, 500)
    }

    private fun stopServer() {
        stopService(Intent(this, app.grom.server.service.GromService::class.java))
        handler.postDelayed({ render() }, 500)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Keeps the preference plumbing out of the activity body. */
    private object GromPrefsHolder {
        fun startOnBoot(activity: Activity): Boolean =
            app.grom.server.service.GromPrefs.startOnBoot(activity)

        fun toggleBoot(activity: Activity) {
            val current = app.grom.server.service.GromPrefs.startOnBoot(activity)
            app.grom.server.service.GromPrefs.setStartOnBoot(activity, !current)
        }
    }

    companion object {
        private const val REFRESH_MS = 1_000L
        private const val REQUEST_NOTIFICATIONS = 47001
    }
}
