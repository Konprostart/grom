// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import android.content.Context
import android.content.Intent
import android.os.Build
import app.grom.server.core.BindPolicy
import app.grom.server.core.ControlPort
import app.grom.server.service.GromPrefs
import app.grom.server.service.GromService
import app.grom.sdk.GromClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The zero-config entry point for an application that embeds the engine (D55).
 *
 * A host application depends on `:engine`, calls [recordConsent] after showing
 * the operator what will happen, and then calls [start]. The server runs as a
 * foreground service inside the host's own package, so there is no second
 * installation, no IPC permission and no signature mismatch: the caller and the
 * service are the same UID.
 *
 * The engine never starts itself. A persistent server is a visible decision,
 * which is why [start] refuses without recorded consent rather than beginning
 * an always-on process silently.
 *
 * Honesty rules that apply here exactly as they do to the standalone app:
 *   - `start` reports that the start was *requested*, not that the agent is
 *     ready (the runtime may be missing, in which case status says so);
 *   - registration reports success only when the Binder call really returned
 *     a stored project record;
 *   - the host is told the server role is embedded, so it can present its own
 *     UI; Grom's own screens are not part of the library.
 */
object GromEmbed {

    private const val PREFS = "grom.embed"
    private const val KEY_CONSENT = "consent"

    /**
     * What the host registers. `projectId` follows the plugin-id shape
     * (`^[a-z][a-z0-9-]{1,31}$`); the engine rejects anything else and never
     * stores a path, a secret or free-form metadata.
     *
     * [heartbeat] repeats the announcement for as long as the server runs. That
     * is what keeps the dashboard honest: `alive` lasts 90 seconds from the last
     * announcement, so a host that dies goes `stale` on its own (D55, D61).
     */
    data class Options(
        val projectId: String,
        val projectTitle: String,
        val projectVersion: String = "",
        val capabilities: Set<String> = emptySet(),
        val heartbeat: Boolean = true,
        val heartbeatIntervalMs: Long = GromProject.DEFAULT_HEARTBEAT_MS,
        /**
         * The loopback control port this host wants (D85).
         *
         * The default is the standalone server's 47001, and a phone can hold
         * both. A host that ships its own copy of Grom should choose another
         * port (47101, say) so the two servers never fight over one address.
         * `null` keeps whatever the host chose earlier, or the default.
         */
        val controlPort: Int? = null
    )

    /** `requested` means the service start was accepted by the platform. */
    data class StartResult(val requested: Boolean, val reason: String? = null)

    /**
     * The live heartbeat for this process, if one is running. One engine per
     * process, so one session is enough; a second [start] replaces it.
     */
    private class ProjectSession(val client: GromClient) {
        @Volatile
        var stopHeartbeat: (() -> Unit)? = null
    }

    @Volatile
    private var session: ProjectSession? = null

    fun consentGiven(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT, false)

    /**
     * The control port this app is configured to serve (D85). Defaults to the
     * standalone server's port, so an embedding host that does not care and an
     * installed Grom collide exactly once, visibly, instead of silently.
     */
    fun controlPort(context: Context): Int = GromPrefs.port(context)

    /**
     * Chooses the control port without starting anything. Returns `false` when
     * the value is not a usable port; it does not probe (see [start], which does).
     */
    fun setControlPort(context: Context, port: Int): Boolean {
        if (!BindPolicy.isAllowedPort(port)) return false
        GromPrefs.setPort(context.applicationContext, port)
        return true
    }

    /** The host must call this only after it has told the operator what starts. */
    fun recordConsent(context: Context, granted: Boolean = true) {
        prefs(context).edit().putBoolean(KEY_CONSENT, granted).apply()
    }

    /**
     * Starts the embedded server.
     *
     * When [options] is supplied, a background thread binds to the service,
     * announces the project and — unless `options.heartbeat` is off — keeps
     * announcing at that interval until [stop] is called. [onRegistered] is
     * called with `true` only if the Binder call stored the record. The callback
     * runs on that background thread, never on the main thread.
     */
    fun start(
        context: Context,
        options: Options? = null,
        onRegistered: ((Boolean) -> Unit)? = null
    ): StartResult {
        if (!consentGiven(context)) {
            return StartResult(false, "CONSENT_REQUIRED")
        }
        val app = context.applicationContext

        // D85. The port is chosen and probed before the service is asked to
        // start, so the answer is a code the host can act on rather than a
        // process that dies a second later. Our *own* listener must not be
        // mistaken for a conflict: the service records the port it serves.
        options?.controlPort?.let { port ->
            if (!BindPolicy.isAllowedPort(port)) {
                return StartResult(false, ControlPort.NOT_ALLOWED)
            }
            val serving = GromService.currentServingPort()
            if (serving > 0 && serving != port) {
                return StartResult(false, PORT_CHANGE_RESTART_REQUIRED)
            }
            GromPrefs.setPort(app, port)
        }
        val chosen = GromPrefs.port(app)
        if (!GromService.isServing(chosen)) {
            ControlPort.refusal(chosen)?.let { return StartResult(false, it) }
        }

        val intent = Intent(app, GromService::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            if (options != null) startProjectLoop(app, options, onRegistered)
            StartResult(true)
        } catch (failure: Exception) {
            StartResult(false, "START_REFUSED: ${failure.javaClass.simpleName}")
        }
    }

    /** Stops the embedded server and the heartbeat. State stays on disk. */
    fun stop(context: Context) {
        stopHeartbeat()
        val app = context.applicationContext
        runCatching { app.stopService(Intent(app, GromService::class.java)) }
    }

    /** Stops only the repeating announcement; the server keeps running. */
    fun stopHeartbeat() {
        val current = session ?: return
        current.stopHeartbeat?.invoke()
        current.stopHeartbeat = null
        current.client.unbind()
        session = null
    }

    /**
     * A typed project handle over a client the caller binds. Use this when the
     * host wants to control its own binding, e.g. to read and write local data.
     */
    fun newProject(client: GromClient, projectId: String): GromProject =
        GromProject(client, projectId)

    /** A client bound to the host's own service (same package, same UID). */
    fun newClient(context: Context): GromClient =
        GromClient(context.applicationContext, GromClient.Target.EMBEDDED)

    private fun startProjectLoop(
        context: Context,
        options: Options,
        onRegistered: ((Boolean) -> Unit)?
    ) {
        stopHeartbeat()
        val client = newClient(context)
        val registration = GromProject.Registration(
            title = options.projectTitle,
            version = options.projectVersion,
            packageName = context.packageName,
            capabilities = options.capabilities
        )
        val current = ProjectSession(client)
        session = current

        val thread = Thread({
            val latch = CountDownLatch(1)
            // The latch's countDown/await pair establishes a happens-before
            // edge, so the value is safely visible to the reading thread.
            var registered = false
            val bound = client.bind(object : GromClient.Listener {
                override fun onConnected(apiVersion: Int) {
                    registered = GromProject(client, options.projectId).announce(registration)
                    latch.countDown()
                }

                override fun onDisconnected(reason: String) {
                    latch.countDown()
                }
            })
            if (!bound) latch.countDown()
            runCatching { latch.await(15, TimeUnit.SECONDS) }
            onRegistered?.invoke(registered)

            if (!registered || !options.heartbeat) {
                client.unbind()
                if (session === current) session = null
                return@Thread
            }
            if (session !== current) {
                // A newer start replaced this session; it owns the binder now.
                client.unbind()
                return@Thread
            }
            // The heartbeat reuses the same binder. It is GromProject's
            // implementation, so the interval clamp and the dead-binder check
            // are the ones the unit tests cover.
            current.stopHeartbeat = GromProject(client, options.projectId)
                .startHeartbeat(registration, options.heartbeatIntervalMs)
        }, "grom-embed-register")
        thread.isDaemon = true
        thread.start()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A port change needs a stop/start cycle; the running listener keeps its port. */
    const val PORT_CHANGE_RESTART_REQUIRED: String = "PORT_CHANGE_RESTART_REQUIRED"
}
