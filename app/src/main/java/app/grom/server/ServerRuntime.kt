// SPDX-License-Identifier: Apache-2.0
package app.grom.server

import android.content.Context
import app.grom.server.agent.AgentLauncher
import app.grom.server.agent.NodeAgentProcess
import app.grom.server.audit.AuditStore
import app.grom.server.core.AgentState
import app.grom.server.core.HealthGate
import app.grom.server.core.Redactor
import app.grom.server.core.SupervisorPolicy
import app.grom.server.core.Token
import app.grom.server.http.ApiException
import app.grom.server.http.GromApi
import app.grom.server.http.Json
import app.grom.server.http.LoopbackHttpServer
import app.grom.server.http.Router
import app.grom.server.service.GromPrefs
import app.grom.server.store.SecureStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The running server: HTTP listener + supervisor + audit + token.
 *
 * Everything durable lives under `<files>/grom/`:
 *   grom-token        the bearer token
 *   node/             the agent runtime (installed by the operator)
 *   audit/            audit.log, audit.log.1, audit.head
 */
class ServerRuntime(
    private val context: Context,
    private val logSink: (String) -> Unit = {}
) : GromApi {

    private val rootDir = File(context.filesDir, "grom")
    private val runtimeDir = File(rootDir, "node")

    private val store = SecureStore(rootDir)
    private val auditStore = AuditStore(File(rootDir, "audit"))
    private val policy = SupervisorPolicy()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "grom-supervisor").apply { isDaemon = true }
        }

    private val lock = Any()

    @Volatile
    private var token: String = ""

    @Volatile
    private var startedAt: Long = 0L

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var httpServer: LoopbackHttpServer? = null

    @Volatile
    private var agent: NodeAgentProcess? = null

    @Volatile
    private var backoffUntil: Long = 0L

    @Volatile
    private var lastError: String? = null

    val port: Int get() = GromPrefs.port(context)

    fun isRunning(): Boolean = running

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        synchronized(lock) {
            if (running) return
            rootDir.mkdirs()
            token = store.loadOrCreate()
            startedAt = System.currentTimeMillis()

            if (GromPrefs.isQuarantined(context)) {
                policy.onFatal(GromPrefs.quarantineReason(context) ?: "persisted quarantine")
            }

            val server = LoopbackHttpServer(port, Router(this), logSink)
            server.start() // throws if the bind is not loopback — deliberately fatal
            httpServer = server
            running = true

            scheduler.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)

            if (!policy.isQuarantined()) {
                startAgentLocked()
            }
            safeAudit("server.start", "port=${server.boundPort}")
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            safeAudit("server.stop", "")
            running = false
            httpServer?.stop()
            httpServer = null
            agent?.stop()
            agent = null
            policy.onStopped()
            scheduler.shutdownNow()
        }
    }

    fun stateName(): String = when (policy.state) {
        AgentState.READY -> "READY"
        AgentState.STARTING, AgentState.BACKOFF -> "STARTING"
        AgentState.MISSING_RUNTIME -> "MISSING_RUNTIME"
        AgentState.QUARANTINED -> "QUARANTINED"
        AgentState.STOPPED -> "STOPPED"
    }

    fun tokenFingerprint(): String = if (token.isEmpty()) "\u2014" else Token.fingerprint(token)

    fun tokenValue(): String = token

    fun lastError(): String? = lastError

    // ------------------------------------------------------------------ GromApi

    override fun apiVersion(): Int = 1

    override fun authorize(candidate: String?): Boolean =
        candidate != null && Token.matches(token, candidate)

    override fun health(): Map<String, Any?> = buildMap {
        put("api", 1)
        put("state", stateName())
        put("runtime", if (runtime().present) "node" else "missing")
        put("pid", agent?.pid ?: -1L)
        put("uptimeMs", if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt)
        put("version", BuildConfig.VERSION_NAME)
    }

    override fun status(): Map<String, Any?> {
        synchronized(lock) {
            val missing = runtime().missingFile()
            return health() + mapOf(
                "restarts" to policy.restarts,
                "crashes" to policy.crashCount(),
                "lastCrashMs" to (policy.lastCrashAt ?: -1L),
                "quarantineReason" to policy.quarantineReason,
                "port" to port,
                "enabled" to GromPrefs.startOnBoot(context),
                "runtimeDir" to runtimeDir.absolutePath,
                "missing" to missing,
                "lastError" to lastError
            )
        }
    }

    override fun auditTail(limit: Int): Map<String, Any?> {
        val entries = auditStore.tail(limit)
        return mapOf(
            "entries" to entries.map { entry ->
                mapOf(
                    "seq" to entry.seq,
                    "ts" to entry.ts,
                    "actor" to entry.actor,
                    "action" to entry.action,
                    "detail" to entry.detail,
                    "prev" to entry.prev,
                    "hash" to entry.hash
                )
            },
            "head" to auditStore.headHash()
        )
    }

    override fun auditVerify(): Map<String, Any?> {
        val result = auditStore.verify()
        return mapOf(
            "ok" to result.ok,
            "entries" to result.entries,
            "head" to result.head,
            "brokenAt" to result.brokenAt
        )
    }

    override fun rotateToken(): String {
        synchronized(lock) {
            val fresh = store.rotate()
            token = fresh
            audited("token.rotate", "fingerprint=${Token.fingerprint(fresh)}")
            return fresh
        }
    }

    override fun startAgent(): Map<String, Any?> {
        synchronized(lock) {
            requireRuntimeOrThrow()
            if (policy.isQuarantined()) {
                throw ApiException(
                    409,
                    "QUARANTINED",
                    "agent is quarantined after ${policy.crashCount()} crashes; reset first"
                )
            }
            if (policy.state == AgentState.READY) {
                throw ApiException(409, "ALREADY_RUNNING", "agent is already running")
            }
            audited("agent.start", "state=${stateName()}")
            startAgentLocked()
            return status()
        }
    }

    override fun stopAgent(): Map<String, Any?> {
        synchronized(lock) {
            agent?.stop()
            agent = null
            policy.onStopped()
            backoffUntil = 0L
            audited("agent.stop", "")
            return status()
        }
    }

    override fun restartAgent(): Map<String, Any?> {
        synchronized(lock) {
            requireRuntimeOrThrow()
            if (policy.isQuarantined()) {
                throw ApiException(409, "QUARANTINED", "agent is quarantined; reset first")
            }
            agent?.stop()
            agent = null
            audited("agent.restart", "")
            startAgentLocked()
            return status()
        }
    }

    override fun resetAgent(): Map<String, Any?> {
        synchronized(lock) {
            agent?.stop()
            agent = null
            policy.reset()
            backoffUntil = 0L
            GromPrefs.setQuarantined(context, null)
            audited("agent.reset", "quarantine cleared")
            return status()
        }
    }

    override fun exec(op: String, argsJson: String?): Map<String, Any?> {
        synchronized(lock) {
            requireRuntimeOrThrow()
            if (policy.isQuarantined()) {
                throw ApiException(409, "QUARANTINED", "agent is quarantined; reset first")
            }
            if (policy.state != AgentState.READY) {
                throw ApiException(503, "AGENT_NOT_READY", "agent state is ${stateName()}")
            }
            if (op !in ALLOWED_OPS) {
                throw ApiException(400, "UNKNOWN_OP", "op is not whitelisted: $op")
            }
            audited("agent.exec", "op=$op")
            return mapOf("op" to op, "ok" to true)
        }
    }

    // ---------------------------------------------------------------- supervisor

    private fun tick() {
        try {
            synchronized(lock) {
                if (!running) return
                policy.onHealthyTick()

                if (policy.state == AgentState.STARTING && policy.startTimedOut()) {
                    handleCrash("start timeout")
                    return
                }

                val current = agent
                if (current != null && policy.state in LIVE_STATES && !current.isAlive()) {
                    handleCrash("agent exited (code=${current.exitValue()})")
                    return
                }

                if (policy.state == AgentState.BACKOFF && System.currentTimeMillis() >= backoffUntil) {
                    startAgentLocked()
                }
            }
        } catch (unexpected: Throwable) {
            lastError = unexpected.message
            log("supervisor tick failed: ${unexpected.javaClass.simpleName}")
        }
    }

    private fun startAgentLocked() {
        if (policy.isQuarantined()) return
        val runtime = runtime()
        if (!runtime.present) {
            policy.onMissingRuntime()
            lastError = "missing ${runtime.missingFile()}"
            return
        }
        val process = ensureAgent()
        val state = policy.startRequested(true)
        if (state != AgentState.STARTING) return
        val launched = process.launch(port, token)
        if (!launched) {
            handleCrash("launch failed")
            return
        }
        lastError = null
        log("agent launched pid=${process.pid}")
    }

    private fun handleCrash(cause: String) {
        val state = policy.onCrash(cause)
        agent?.stop()
        agent = null
        val detail = "cause=${cause} crashes=${policy.crashCount()}"
        safeAudit("agent.crash", detail)
        if (state == AgentState.QUARANTINED) {
            GromPrefs.setQuarantined(context, policy.quarantineReason)
            log("agent quarantined: ${policy.quarantineReason}")
        } else {
            backoffUntil = System.currentTimeMillis() + policy.backoffMs()
        }
    }

    private fun ensureAgent(): NodeAgentProcess {
        agent?.let { return it }
        val created = NodeAgentProcess(
            runtimeDirectory = runtimeDir,
            homeDirectory = File(context.filesDir, "home"),
            tmpDirectory = context.cacheDir,
            parentPid = android.os.Process.myPid().toLong(),
            logger = { line -> log(redactor().redact(line)) },
            onStdoutLine = { line -> onAgentLine(line) },
            onStderrLine = { line -> log("agent: ${redactor().redact(line)}") }
        )
        agent = created
        return created
    }

    private fun onAgentLine(rawLine: String) {
        val line = redactor().redact(rawLine)
        when (val observation = HealthGate.observe(rawLine)) {
            is HealthGate.Observation.Ready -> {
                synchronized(lock) {
                    val expected = agent?.pid ?: -1L
                    if (expected > 0 && observation.pid != expected) {
                        handleCrash("ready pid mismatch")
                        return
                    }
                    policy.onReady()
                    safeAudit("agent.ready", "pid=${observation.pid}")
                }
            }
            is HealthGate.Observation.Fatal -> {
                synchronized(lock) {
                    policy.onFatal(observation.message)
                    GromPrefs.setQuarantined(context, policy.quarantineReason)
                    agent?.stop()
                    agent = null
                    safeAudit("agent.fatal", redactor().redact(observation.message))
                }
            }
            HealthGate.Observation.Unknown -> log("agent: $line")
        }
    }

    // ------------------------------------------------------------------- helpers

    private fun runtime(): AgentLauncher.RuntimeFiles = AgentLauncher.RuntimeFiles(runtimeDir)

    private fun requireRuntimeOrThrow() {
        val missing = runtime().missingFile()
        if (missing != null) {
            throw ApiException(
                503,
                "MISSING_RUNTIME",
                "agent runtime is not installed: missing $missing (see scripts/build-node-android.sh)"
            )
        }
    }

    private fun audited(action: String, detail: String) {
        try {
            auditStore.append(actor = actor(), action = action, detail = redactor().redact(detail))
        } catch (failure: Exception) {
            // S2-R034: no state change without a record.
            throw ApiException(500, "AUDIT_FAILED", "audit append failed; request aborted")
        }
    }

    private fun safeAudit(action: String, detail: String) {
        try {
            auditStore.append(actor = "supervisor", action = action, detail = redactor().redact(detail))
        } catch (_: Exception) {
            log("audit append failed for $action")
        }
    }

    private fun actor(): String = "api:${tokenFingerprint()}"

    private fun redactor(): Redactor = Redactor(listOfNotNull(token.ifEmpty { null }))

    private fun log(message: String) {
        // The sink decides where this goes; the token never reaches it (D26).
        logSink(message)
    }

    companion object {
        private const val TICK_MS = 1_000L
        private val LIVE_STATES = setOf(AgentState.STARTING, AgentState.READY)
        private val ALLOWED_OPS = setOf("ping", "echo")
    }
}
