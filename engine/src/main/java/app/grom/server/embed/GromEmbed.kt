// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import android.content.Context
import android.content.Intent
import android.os.Build
import app.grom.server.core.BindPolicy
import app.grom.server.core.ControlPort
import app.grom.server.doctor.AndroidDoctorProbe
import app.grom.server.doctor.DoctorFacade
import app.grom.server.doctor.DoctorReport
import app.grom.server.doctor.RunMarkerStore
import app.grom.server.http.JsonParser
import app.grom.server.service.GromPrefs
import app.grom.server.service.GromService
import app.grom.sdk.GromClient
import java.io.File
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
 * Two calls are enough for a host that does not want to learn this engine:
 *
 * ```kotlin
 * val result = GromEmbed.start(context, GromEmbed.Options(projectId, title,
 *     controlPort = GromEmbed.AUTO_PORT))
 * if (!result.requested) { /* result.reason is a code with a known action */ }
 * GromEmbed.health(context) { health -> /* health.headline + health.advice */ }
 * ```
 *
 * [health]/[measureHealth] return a projected Doctor report — stable codes and
 * the action that addresses each — so the host does not have to parse a status
 * document or invent a diagnosis (D90).
 *
 * Honesty rules that apply here exactly as they do to the standalone app:
 *   - `start` reports that the start was *requested*, not that the agent is
 *     ready (the runtime may be missing, in which case status says so);
 *   - registration reports success only when the Binder call really returned
 *     a stored project record;
 *   - [measureHealth] reports what the Doctor measured; a state is never
 *     greener than the measurement behind it;
 *   - the host is told the server role is embedded, so it can present its own
 *     UI; Grom's own screens are not part of the library.
 */
object GromEmbed {

    private const val PREFS = "grom.embed"
    private const val KEY_CONSENT = "consent"
    private const val KEY_CONSENT_VERSION = "consent.notice"
    private const val KEY_CONSENT_AT = "consent.at"

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
         * The loopback control port this host wants (D85, D89).
         *
         * [ControlPort.AUTO_PORT] (`0`) means "pick one for me": a free port from
         * `47100..47199`, remembered between runs. A specific number is used
         * exactly; `null` keeps the stored choice (the standalone app's 47001 by
         * default, which collides with an installed Grom).
         */
        val controlPort: Int? = null,
        /**
         * The version of the consent notice the host showed (D88). Empty means
         * the host does not version its notice — the pre-D88 behaviour.
         */
        val noticeVersion: String = ""
    )

    /**
     * The result of [start].
     *
     * [requested] means the platform accepted the foreground-service start; it
     * is not a health claim. When it is false, [reason] is a stable code and
     * [detail] may carry the platform's own class name.
     */
    data class StartResult(
        val requested: Boolean,
        val reason: String? = null,
        val detail: String? = null,
        /** The port the server was asked to serve; `0` when no port was resolved. */
        val port: Int = 0
    )

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

    // ---------------------------------------------------------------- consent

    /**
     * True when an answer was recorded, whatever the notice version.
     *
     * A host should prefer [consentRecord] when it versions its notice: this
     * flag alone cannot tell an old answer from a current one.
     */
    fun consentGiven(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT, false)

    /** What the operator answered and which notice text it covered (D88). */
    fun consentRecord(context: Context): EmbedConsent.Record? =
        EmbedConsent.record(
            granted = prefs(context).getBoolean(KEY_CONSENT, false),
            noticeVersion = prefs(context).getString(KEY_CONSENT_VERSION, "").orEmpty(),
            grantedAtMs = prefs(context).getLong(KEY_CONSENT_AT, 0L)
        )

    /**
     * Records the operator's answer.
     *
     * Call this only after the host has told the operator what starts. A
     * `false` answer is recorded as well: it clears any previous consent, so
     * "no" is not silently overwritten by an old "yes".
     */
    fun recordConsent(
        context: Context,
        noticeVersion: String,
        granted: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putBoolean(KEY_CONSENT, granted)
            .putString(KEY_CONSENT_VERSION, if (granted) noticeVersion.trim() else "")
            .putLong(KEY_CONSENT_AT, if (granted) nowMs else 0L)
            .apply()
    }

    /**
     * Records an answer from a host that does not version its notice.
     *
     * Kept for the hosts written before D88; equivalent to passing an empty
     * notice version. A host that later starts declaring a version is asked to
     * show the notice once more, which is the point of versioning it.
     */
    fun recordConsent(context: Context, granted: Boolean = true) {
        recordConsent(context, noticeVersion = "", granted = granted)
    }

    // ------------------------------------------------------------------- port

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

    // ------------------------------------------------------------------ start

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
        val app = context.applicationContext

        // D88: a recorded answer covers the notice text the host is starting now.
        // A host that changed what it shows must show it again.
        EmbedConsent.refusal(consentRecord(app), options?.noticeVersion.orEmpty())?.let {
            return StartResult(false, it)
        }

        // D85/D89. The port is resolved and probed before the service is asked
        // to start, so the answer is a code the host can act on rather than a
        // process that dies a second later. Our *own* listener must not be
        // mistaken for a conflict: the service records the port it serves.
        val stored = GromPrefs.port(app)
        val choice = ControlPort.resolve(
            requested = options?.controlPort,
            stored = stored,
            serving = GromService.currentServingPort(),
            isFree = { ControlPort.isFree(it) }
        )
        choice.refusal?.let { return StartResult(false, it, port = choice.port) }
        if (choice.port != stored) GromPrefs.setPort(app, choice.port)

        val intent = Intent(app, GromService::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            if (options != null) startProjectLoop(app, options, onRegistered)
            StartResult(true, port = choice.port)
        } catch (failure: Exception) {
            StartResult(
                requested = false,
                reason = startFailureCode(failure),
                detail = failure.javaClass.simpleName,
                port = choice.port
            )
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

    // ----------------------------------------------------------------- health

    /**
     * One measured health summary, off the calling thread (D90).
     *
     * The callback always runs: when nothing could be measured the state is
     * [EmbedState.UNKNOWN] rather than silence, because a host waiting forever
     * for an answer would show a spinner over a dead server.
     */
    fun health(context: Context, callback: (EmbedHealth) -> Unit) {
        val app = context.applicationContext
        val thread = Thread({
            val health = try {
                EmbedHealth.of(measureReport(app))
            } catch (_: Throwable) {
                EmbedHealth(EmbedState.UNKNOWN, EmbedHealth.PARTLY_UNKNOWN)
            }
            runCatching { callback(health) }
        }, "grom-embed-health")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * The blocking form of [health].
     *
     * Waits at most two seconds for the local Binder, so it must **not** be
     * called from the main thread. It never starts the server: when nothing is
     * running the report simply has no status, and the Doctor says so.
     */
    fun measureHealth(context: Context): EmbedHealth =
        EmbedHealth.of(measureReport(context.applicationContext))

    /**
     * The full Doctor report behind [measureHealth], for a host that draws a
     * detail screen. Contains stable codes only — no credential, address or free
     * text (D80, S4-R050).
     *
     * Like [measureHealth] it waits for the local Binder, so it belongs on a
     * background thread; use [health] when the caller is the UI thread.
     */
    fun doctorReport(context: Context): DoctorReport? =
        runCatching { measureReport(context.applicationContext) }.getOrNull()

    private fun measureReport(app: Context): DoctorReport {
        val status = statusOrNull(app)
        val marker = RunMarkerStore(File(File(app.filesDir, "grom"), RunMarkerStore.FILE_NAME))
            .current()
        return DoctorFacade.measure(
            probe = AndroidDoctorProbe(app).facts(),
            status = status,
            marker = marker
        )
    }

    /**
     * The server's own status document, or `null` when it is not running.
     *
     * `createIfMissing = false` is what keeps a health check from starting a
     * server the operator did not ask for: binding with `BIND_AUTO_CREATE` would
     * create the service, and "checking" would silently become "starting".
     */
    private fun statusOrNull(app: Context): Map<String, Any?>? {
        val client = newClient(app)
        val latch = CountDownLatch(1)
        var text: String? = null
        val bound = client.bind(
            object : GromClient.Listener {
                override fun onConnected(apiVersion: Int) {
                    text = client.call(null) { it.statusJson() }
                    latch.countDown()
                }

                override fun onDisconnected(reason: String) {
                    latch.countDown()
                }
            },
            createIfMissing = false
        )
        if (!bound) {
            client.unbind()
            return null
        }
        runCatching { latch.await(HEALTH_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        client.unbind()
        return text?.let { JsonParser.parseObjectOrNull(it) }
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

    /**
     * A stable code for a refused start.
     *
     * `ForegroundServiceStartNotAllowedException` has its own code because it is
     * the one failure the operator can fix by opening the app: it used to be
     * buried in a `START_REFUSED: <class>` string, which reads like a crash.
     */
    internal fun startFailureCode(failure: Throwable): String {
        val name = failure.javaClass.simpleName
        return if (name.contains("ForegroundServiceStartNotAllowed")) {
            EmbedHealth.FOREGROUND_START_NOT_ALLOWED
        } else {
            EmbedHealth.START_REFUSED
        }
    }

    /** The sentinel for "choose a free control port for me" (D89). */
    const val AUTO_PORT: Int = ControlPort.AUTO_PORT

    /** A port change needs a stop/start cycle; the running listener keeps its port. */
    const val PORT_CHANGE_RESTART_REQUIRED: String = ControlPort.RESTART_REQUIRED

    /** How long [measureHealth] waits for the local Binder before calling it unknown. */
    const val HEALTH_BIND_TIMEOUT_MS: Long = 2_000L
}
