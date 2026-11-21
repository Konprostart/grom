// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The in-process reverse SSH tunnel (D58).
 *
 * Grom is the SSH *client*. It opens a session to an operator-owned host and
 * requests exactly one thing: a remote `tcpip-forward` from that host back to
 * the local data plane. It never opens a shell, an exec channel, a subsystem,
 * agent forwarding or a local/dynamic forward, so the session cannot be used to
 * run anything on the VPS or to reach anything except the Grom port.
 *
 * The host key is pinned on first use (TOFU). A pinned key that changes is a
 * hard refusal (`HOST_KEY_MISMATCH`); the operator must clear the pin
 * deliberately. A target with no pin is refused at connect unless the caller
 * passes the fingerprint it saw, which is how the UI asks for confirmation.
 *
 * Nothing here is optimistic: `CONNECTED` is set only after the server has
 * acknowledged the remote forward. Every other path reports a stable code.
 */
class SshTunnelManager(
    private val store: SshTargetStore,
    private val audit: (String, String) -> Unit,
    private val logger: (String) -> Unit,
    /**
     * Where the remote port should be forwarded to on this device. Null means
     * the data plane is not running, in which case a tunnel would be a lie.
     */
    private val localTarget: () -> Pair<String, Int>?,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    enum class State(val wireName: String) {
        DISCONNECTED("disconnected"),
        CONNECTING("connecting"),
        CONNECTED("connected"),
        FAILED("failed")
    }

    /** A snapshot of one target, for the UI or for the authenticated status. */
    data class TunnelInfo(
        val id: String,
        val label: String,
        val host: String,
        val port: Int,
        val username: String,
        val forwardPort: Int,
        val state: State,
        val sinceMs: Long,
        val lastError: String?,
        val hostKeySha256: String?
    )

    sealed class Result {
        data class Connected(val fingerprint: String, val learned: Boolean) : Result()
        data class HostKeyUnverified(val fingerprint: String) : Result()
        data class HostKeyMismatch(val pinned: String, val observed: String) : Result()
        data class Failed(val code: String, val message: String) : Result()
    }

    private class Active(
        val target: SshTarget,
        val client: SshClient,
        val session: ClientSession,
        val sinceMs: Long,
        @Volatile var stoppedByOperator: Boolean = false
    )

    private val admission = Any()
    private val active = ConcurrentHashMap<String, Active>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var failures: Map<String, Pair<String, Long>> = emptyMap()

    fun state(id: String): State = when {
        active.containsKey(id) -> State.CONNECTED
        connecting.contains(id) -> State.CONNECTING
        failures.containsKey(id) -> State.FAILED
        else -> State.DISCONNECTED
    }

    fun isActive(id: String): Boolean = active.containsKey(id)

    fun activeCount(): Int = active.size

    /** For the phone: includes the host and the user. Never leaves the device. */
    fun statusForUi(): List<Map<String, Any?>> {
        val now = clock()
        return store.list().map { target ->
            val current = active[target.id]
            val failure = failures[target.id]
            linkedMapOf(
                "id" to target.id,
                "label" to target.label,
                "host" to target.host,
                "port" to target.port,
                "username" to target.username,
                "auth" to target.auth.wireName,
                "configured" to target.isConfigured(),
                "forwardPort" to target.remoteForwardPort,
                "hostKeySha256" to target.hostKeySha256,
                "publicKeyLine" to target.publicKeyLine,
                "state" to state(target.id).wireName,
                "sinceMs" to (current?.sinceMs ?: failure?.second ?: 0L),
                "lastError" to failure?.first,
                "nowMs" to now
            )
        }
    }

    /**
     * For `/v1/status`: label and measured state only. The host, the user and
     * the fingerprint stay on the device; the remote bridge does not need them.
     */
    fun statusForServer(): Map<String, Any?> {
        val tunnels = store.list().map { target ->
            val current = active[target.id]
            linkedMapOf<String, Any?>(
                "id" to target.id,
                "label" to target.label,
                "state" to state(target.id).wireName,
                "sinceMs" to (current?.sinceMs ?: failures[target.id]?.second ?: 0L),
                "lastError" to failures[target.id]?.first
            )
        }
        val anyConnected = tunnels.any { it["state"] == State.CONNECTED.wireName }
        return linkedMapOf(
            "configured" to store.list().isNotEmpty(),
            "connected" to anyConnected,
            "active" to active.size,
            "tunnels" to tunnels
        )
    }

    // ------------------------------------------------------------------ connect

    /**
     * Blocking; call from a background thread. [trustHostKey] is the fingerprint
     * the operator confirmed for an unpinned target.
     */
    fun connect(id: String, trustHostKey: String? = null): Result {
        val target = store.find(id)
            ?: return Result.Failed("TARGET_NOT_FOUND", "no SSH target with that id")
        if (!target.enabled) {
            return Result.Failed("TARGET_DISABLED", "this target is disabled")
        }
        if (target.auth == SshAuth.KEY && !SshKeys.isSupported()) {
            return Result.Failed(
                "SSH_UNAVAILABLE",
                "this build has no Ed25519 provider; key authentication cannot be used"
            )
        }

        synchronized(admission) {
            if (active.containsKey(id)) {
                return Result.Failed("ALREADY_CONNECTED", "this tunnel is already connected")
            }
            if (!connecting.add(id)) {
                return Result.Failed("CONNECTING", "a connection attempt is already running")
            }
            if (active.size + connecting.size > SshTarget.MAX_ACTIVE) {
                connecting.remove(id)
                return Result.Failed(
                    "TOO_MANY_TUNNELS",
                    "at most ${SshTarget.MAX_ACTIVE} tunnels may be active at once"
                )
            }
        }

        try {
            val local = localTarget()
                ?: return fail(
                    id,
                    "DATA_PLANE_NOT_RUNNING",
                    "start the public data plane before opening a tunnel"
                )
            val secret = store.openSecret(target)
                ?: return fail(
                    id,
                    if (target.auth == SshAuth.KEY) "KEY_MISSING" else "PASSWORD_MISSING",
                    "no usable credential is stored for this target"
                )
            val pairs = if (target.auth == SshAuth.KEY) {
                SshKeys.keyPairFromPrivateText(secret)?.let { listOf(it) }
                    ?: return fail(id, "KEY_INVALID", "the stored private key cannot be parsed")
            } else {
                null
            }
            return open(target, local, secret, pairs, trustHostKey)
        } finally {
            connecting.remove(id)
        }
    }

    private fun open(
        target: SshTarget,
        local: Pair<String, Int>,
        secret: String,
        pairs: List<java.security.KeyPair>?,
        trustHostKey: String?
    ): Result {
        val observed = AtomicReference<String?>(null)
        val mismatch = AtomicReference(false)

        val client = SshClient.setUpDefaultClient()
        // MINA rejects an incoming forwarded-tcpip channel when the client has no
        // forwarding filter, and an accept-all filter would let a compromised
        // server reach any port on the phone. This policy allows exactly one
        // destination: the data plane we asked to expose (D58).
        client.forwardingFilter = SshForwardingPolicy(local.second)
        client.serverKeyVerifier = ServerKeyVerifier { _, _, key: PublicKey ->
            val fingerprint = SshKeys.fingerprint(key)
            observed.set(fingerprint)
            val pinned = target.hostKeySha256
            val trust = trustHostKey?.trim()
            val accepted = when {
                pinned != null -> constantTimeEquals(pinned, fingerprint)
                trust != null -> constantTimeEquals(trust, fingerprint)
                else -> false
            }
            if (!accepted && pinned != null) mismatch.set(true)
            accepted
        }

        var session: ClientSession? = null
        try {
            client.start()
            val connect = client.connect(target.username, target.host, target.port)
            connect.verify(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val established: ClientSession = connect.session
            session = established

            if (pairs != null) {
                established.keyIdentityProvider = KeyIdentityProvider { pairs }
            } else {
                established.addPasswordIdentity(secret)
            }
            established.auth().verify(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            // The only thing this session is allowed to do (D58).
            established.startRemotePortForwarding(
                SshdSocketAddress("127.0.0.1", target.remoteForwardPort),
                SshdSocketAddress(local.first, local.second)
            )

            val fingerprint = observed.get() ?: "SHA256:unavailable"
            val learned = target.hostKeySha256 == null
            if (learned) {
                store.pinHostKey(target.id, fingerprint)
                audit("ssh.hostkey.learned", "target=${target.id}")
            }

            val current = Active(target, client, established, clock())
            active[target.id] = current
            failures = failures - target.id
            established.addSessionListener(object : SessionListener {
                override fun sessionClosed(closed: Session) {
                    val gone = active.remove(target.id) ?: return
                    runCatching { gone.client.stop() }
                    if (!gone.stoppedByOperator) {
                        failures = failures + (target.id to ("SSH_SESSION_CLOSED" to clock()))
                        audit("ssh.closed", "target=${target.id}")
                    }
                }
            })
            audit(
                "ssh.connect",
                "target=${target.id} host=${target.host}:${target.port} " +
                    "forward=127.0.0.1:${target.remoteForwardPort}"
            )
            logger("ssh tunnel up: ${target.label} -> 127.0.0.1:${target.remoteForwardPort}")
            return Result.Connected(fingerprint, learned)
        } catch (failure: Throwable) {
            runCatching { session?.close(true) }
            runCatching { client.stop() }
            val fingerprint = observed.get()
            if (fingerprint != null) {
                val pinned = target.hostKeySha256
                if (pinned == null && trustHostKey == null) {
                    audit("ssh.hostkey.unverified", "target=${target.id}")
                    return Result.HostKeyUnverified(fingerprint)
                }
                if (mismatch.get() || (pinned == null && trustHostKey != null)) {
                    failures = failures + (target.id to ("HOST_KEY_MISMATCH" to clock()))
                    audit("ssh.hostkey.mismatch", "target=${target.id}")
                    return Result.HostKeyMismatch(pinned ?: trustHostKey ?: "", fingerprint)
                }
            }
            val code = when (failure) {
                is java.util.concurrent.TimeoutException -> "SSH_TIMEOUT"
                is java.io.IOException -> "SSH_UNAVAILABLE"
                else -> "SSH_FAILED"
            }
            return fail(id = target.id, code = code, message = describe(failure))
        }
    }

    fun disconnect(id: String) {
        val current = active.remove(id) ?: return
        current.stoppedByOperator = true
        runCatching { current.session.close(true) }
        runCatching { current.client.stop() }
        failures = failures - id
        audit("ssh.disconnect", "target=$id")
        logger("ssh tunnel down: ${current.target.label}")
    }

    /** Stops every tunnel; used when the server stops or the app exits. */
    fun stopAll() {
        for (id in active.keys.toList()) {
            disconnect(id)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun fail(id: String, code: String, message: String): Result {
        failures = failures + (id to (code to clock()))
        audit("ssh.failed", "target=$id code=$code")
        logger("ssh failed: target=$id code=$code")
        return Result.Failed(code, message)
    }

    /**
     * The exception class, never its message: a driver message can carry the
     * host, a user name or a key fragment.
     */
    private fun describe(failure: Throwable): String =
        "the SSH session failed (${failure.javaClass.simpleName})"

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(Charsets.UTF_8),
            right.toByteArray(Charsets.UTF_8)
        )

    companion object {
        private const val CONNECT_TIMEOUT_MS: Long = 15_000
        private const val AUTH_TIMEOUT_MS: Long = 15_000
    }
}

/**
 * The client-side forwarding policy of the SSH bridge (D58).
 *
 * It answers `true` for one thing only: a `forwarded-tcpip` channel addressed to
 * loopback on the port of the local data plane. Agent forwarding, X11, local
 * listens and every other destination are refused, so a malicious or
 * misconfigured server cannot use the session to reach another service on the
 * phone. This class is pure and unit-tested without a network.
 */
internal class SshForwardingPolicy(private val localPort: Int) : ForwardingFilter {

    override fun canForwardAgent(session: Session?, requestType: String?): Boolean = false

    override fun canForwardX11(session: Session?, requestType: String?): Boolean = false

    override fun canListen(address: SshdSocketAddress?, session: Session?): Boolean = false

    override fun canConnect(
        type: TcpForwardingFilter.Type?,
        address: SshdSocketAddress?,
        session: Session?
    ): Boolean {
        if (type != TcpForwardingFilter.Type.Forwarded || address == null) return false
        if (address.port != localPort) return false
        val host = address.hostName?.lowercase() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }
}
