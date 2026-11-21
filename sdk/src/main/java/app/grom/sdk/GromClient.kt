// SPDX-License-Identifier: Apache-2.0
package app.grom.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import app.grom.sdk.ipc.IGromServer

/**
 * Client side of the Grom IPC contract (S4-R017…S4-R020).
 *
 * A client signed with a different certificate gets no Binder: the platform
 * refuses the bind on the signature permission and, even if that were bypassed,
 * GromService.onBind returns null. In both cases the listener is told the
 * connection failed rather than left waiting.
 */
class GromClient(
    private val context: Context,
    private val target: Target = Target.STANDALONE
) {

    /**
     * Where the server lives (D55).
     *
     * `STANDALONE` is the shipped Grom app. `EMBEDDED` means the caller *is* the
     * host application that embeds the engine, so the component lives in the
     * caller's own package and the signature gate matches by construction.
     */
    enum class Target { STANDALONE, EMBEDDED }

    interface Listener {
        fun onConnected(apiVersion: Int)
        fun onDisconnected(reason: String)
    }

    var server: IGromServer? = null
        private set

    private var listener: Listener? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val api = IGromServer.Stub.asInterface(binder)
            if (api == null) {
                listener?.onDisconnected("binder unavailable")
                return
            }
            server = api
            val version = try {
                api.apiVersion()
            } catch (_: RemoteException) {
                -1
            }
            listener?.onConnected(version)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            server = null
            listener?.onDisconnected("service disconnected")
        }

        override fun onNullBinding(name: ComponentName?) {
            // The signature gate refused the caller (S4-R008).
            server = null
            bound = false
            listener?.onDisconnected("signature rejected")
        }

        override fun onBindingDied(name: ComponentName?) {
            server = null
            listener?.onDisconnected("binding died")
        }
    }

    /** Returns false when the platform refused the bind outright. */
    fun bind(listener: Listener): Boolean {
        this.listener = listener
        if (bound) return true
        val intent = Intent(ACTION).apply {
            setClassName(serverPackage(), SERVICE_CLASS)
        }
        bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) {
            listener.onDisconnected("bind refused")
        }
        return bound
    }

    /** Idempotent (S4-R018). */
    fun unbind() {
        if (!bound) return
        bound = false
        server = null
        runCatching { context.unbindService(connection) }
    }

    /** Runs a call, mapping a dead binder to a stable message (S4-R019). */
    fun <T> call(fallback: T, block: (IGromServer) -> T): T {
        val api = server ?: return fallback
        return try {
            block(api)
        } catch (_: RemoteException) {
            server = null
            fallback
        }
    }

    private fun serverPackage(): String = when (target) {
        Target.STANDALONE -> SERVER_PACKAGE
        Target.EMBEDDED -> context.packageName
    }

    companion object {
        const val SERVER_PACKAGE: String = "app.grom.server"
        const val SERVICE_CLASS: String = "app.grom.server.service.GromService"
        const val ACTION: String = "app.grom.sdk.ipc.GROM_SERVER"
        const val PERMISSION: String = "app.grom.sdk.ipc.BIND_GROMSERVER"
        /**
         * API 2 added [IGromServer.reloadSuperServer] (D39); API 3 adds the
         * embeddable surface: projects, config export, agent exec and the SSH
         * bridge (D55, D58); API 4 adds the project-scoped key/value path (D61);
         * API 5 adds the operator's notification settings (D65).
         */
        const val API_VERSION: Int = 5
    }
}
