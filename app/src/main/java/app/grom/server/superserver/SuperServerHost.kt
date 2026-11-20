// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

import android.content.Context
import app.grom.server.core.BindResolution
import app.grom.server.core.SuperBindPolicy
import app.grom.server.http.HttpResponse
import app.grom.server.referral.ReferralStore
import app.grom.server.store.AndroidKeystoreSecretBox
import app.grom.server.store.SecretBox
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.PasswordHash
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore
import app.grom.server.superserver.plugins.lightning.LightningBackend
import app.grom.server.superserver.plugins.lightning.LightningBackendFactory
import app.grom.server.superserver.plugins.lightning.LightningPlugin
import java.io.File

/**
 * Owns the lifecycle of the public data plane inside the server process.
 *
 * Everything is rebuilt from the persisted configuration on every start, so a
 * reload can never leave half of an old policy in place. A configuration that
 * fails validation produces a reported error and **no listener** — there is no
 * degraded "bound anyway" state (S5-R008).
 *
 * All fields read by [status] are volatile and the method takes no lock: the UI
 * polls status once a second and must never be able to deadlock the supervisor.
 */
class SuperServerHost(
    context: Context,
    private val controlPort: Int,
    private val agentHealth: () -> Map<String, Any?>,
    private val agentExec: (String, String?) -> HttpResponse,
    private val audit: (String, String) -> Unit,
    private val logger: (String) -> Unit
) {

    private val lock = Any()
    private val configStore = SuperServerConfigStore(File(context.filesDir, "grom"))
    private val secretBox: SecretBox = AndroidKeystoreSecretBox()

    /**
     * The device's local referral record (D50). It describes *this* device, not
     * the data plane, so it is reported only to callers already allowed to see
     * the full status: the loopback control plane and an authenticated
     * `GET /v1/status`. The unauthenticated `/v1/health` never includes it.
     */
    private val appContext: Context = context.applicationContext

    @Volatile
    private var config: SuperServerConfig = SuperServerConfig.defaults()

    @Volatile
    private var server: SuperServer? = null

    @Volatile
    private var registry: PluginRegistry = PluginRegistry(emptyList())

    @Volatile
    private var events: EventBus = EventBus()

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var backendError: String? = null

    @Volatile
    private var startedAtMs: Long = 0L

    fun start() {
        synchronized(lock) {
            stopLocked()
            startLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    fun reload() {
        synchronized(lock) {
            audit("super.reload", "")
            stopLocked()
            startLocked()
        }
    }

    fun isRunning(): Boolean = server?.isRunning() == true

    fun logLastError(): String? = lastError

    /** Snapshot for `GET /v1/status`, `/grom/v1/status` and the UI. */
    fun status(): Map<String, Any?> {
        val current = server
        return mapOf(
            "enabled" to config.enabled,
            "running" to (current?.isRunning() == true),
            "bind" to config.bind.name,
            "address" to (current?.address ?: "not bound"),
            "port" to config.port,
            "auth" to config.auth.name,
            "passwordSet" to (config.passwordHash != null),
            "keys" to config.enabledKeys().map {
                mapOf("keyId" to it.keyId, "label" to it.label)
            },
            "lightning" to config.lightning.kind.wireName,
            "error" to lastError,
            "backendError" to backendError,
            "streams" to events.subscriberCount(),
            "plugins" to registry.all().map {
                mapOf("id" to it.id, "capabilities" to it.capabilities().sorted())
            },
            "referral" to ReferralStore.summary(appContext)
        )
    }

    // ------------------------------------------------------------- lifecycle

    private fun startLocked() {
        val loaded = configStore.load()
        config = loaded
        lastError = configStore.lastError
        backendError = null
        startedAtMs = 0L

        if (!loaded.enabled) {
            logger("super server is disabled")
            return
        }

        val check = loaded.validate(controlPort)
        if (check is ConfigCheck.Invalid) {
            lastError = "${check.code}: ${check.message}"
            logger("super server refused: $lastError")
            audit("super.refused", check.code)
            return
        }

        val resolved = SuperBindPolicy.resolve(loaded.bind)
        if (resolved is BindResolution.Rejected) {
            lastError = "${resolved.code}: ${resolved.message}"
            logger("super server refused: $lastError")
            audit("super.refused", resolved.code)
            return
        }
        val bound = resolved as BindResolution.Bound

        val passwords = loaded.passwordHash?.let { PasswordHash.decode(it) }
        if (loaded.passwordHash != null && passwords == null) {
            lastError = "BAD_PASSWORD_HASH: the stored password hash cannot be decoded"
            logger("super server refused: $lastError")
            return
        }

        val keys = LinkedHashMap<String, ByteArray>()
        for (key in loaded.enabledKeys()) {
            val bytes = key.decodedPublicKey() ?: continue
            keys[key.keyId] = bytes
        }

        val backend = buildBackend(loaded)
        val pluginRegistry = PluginRegistry(
            listOf(
                AgentPlugin(agentHealth, agentExec),
                LightningPlugin(backend, loaded.lightning.kind.wireName, backendError)
            )
        )

        val sessions = SessionStore(ttlMs = loaded.sessionTtlMs)
        val signer = RequestSigner(keyLookup = { id -> keys[id] })
        val auth = SuperAuth(
            config = loaded,
            passwords = passwords,
            sessions = sessions,
            requestLimiter = RateLimiter(),
            loginLimiter = LoginLimiter(),
            signer = signer
        )

        val bus = EventBus()
        startedAtMs = System.currentTimeMillis()

        val router = SuperRouter(
            config = loaded,
            auth = auth,
            plugins = pluginRegistry,
            events = bus,
            uptimeMs = {
                val started = startedAtMs
                if (started == 0L) 0L else System.currentTimeMillis() - started
            },
            serverStatus = {
                mapOf(
                    "address" to (server?.address ?: "not bound"),
                    "port" to config.port,
                    "limits" to mapOf(
                        "maxBodyBytes" to config.maxBodyBytes,
                        "maxConnections" to config.maxConnections,
                        "maxSseClients" to config.maxSseClients
                    ),
                    "agent" to agentHealth(),
                    "streams" to bus.subscriberCount()
                )
            }
        )

        val created = SuperServer(loaded, bound, router, logger)
        try {
            created.start()
        } catch (failure: Exception) {
            // A bind conflict is reported, not retried on another address.
            lastError = "BIND_FAILED: ${failure.javaClass.simpleName}"
            logger("super server bind failed: ${failure.javaClass.simpleName}")
            audit("super.bind.failed", failure.javaClass.simpleName)
            startedAtMs = 0L
            return
        }

        server = created
        registry = pluginRegistry
        events = bus
        audit("super.start", "address=${created.address} bind=${loaded.bind.name} auth=${loaded.auth.name}")
        bus.publish("server", mapOf("state" to "started", "address" to created.address))
        logger("super server started at ${created.address}")
    }

    private fun stopLocked() {
        val current = server ?: return
        runCatching { current.stop() }
        runCatching { events.publish("server", mapOf("state" to "stopped")) }
        server = null
        startedAtMs = 0L
        audit("super.stop", "")
        logger("super server stopped")
    }

    private fun buildBackend(cfg: SuperServerConfig): LightningBackend? {
        if (cfg.lightning.kind == LightningKind.NONE) {
            backendError = null
            return null
        }
        val sealed = cfg.lightning.apiKeySealed
        if (sealed.isNullOrBlank()) {
            backendError = "no backend credential is stored"
            return null
        }
        val plaintext = secretBox.open(sealed)
        if (plaintext.isNullOrBlank()) {
            backendError = "the stored backend credential could not be decrypted"
            logger("lightning backend credential could not be opened")
            return null
        }
        val backend = LightningBackendFactory.create(cfg.lightning, plaintext)
        if (backend == null) {
            backendError = "the backend configuration is incomplete"
        }
        return backend
    }
}
