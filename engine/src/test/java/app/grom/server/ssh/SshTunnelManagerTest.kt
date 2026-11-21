// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import app.grom.server.store.SecretBox
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end proof of D58 against a real SSH server on loopback.
 *
 * Apache MINA SSHD's own server implementation is started in-process, the Grom
 * client connects to it, learns and pins the host key, opens a remote
 * `tcpip-forward`, and a plain TCP client on the "VPS side" reaches a local
 * echo server through the tunnel. Nothing here is mocked except the network
 * host itself.
 */
class SshTunnelManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext.reversed()
        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:").takeIf { sealed.startsWith("sealed:") }?.reversed()
    }

    private lateinit var sshServer: SshServer
    private var sshPort: Int = 0

    private lateinit var echo: ServerSocket
    private lateinit var echoThread: Thread
    private var echoConnections = CountDownLatch(1)

    @Before
    fun setUp() {
        SshKeys.ensureProvider()

        sshServer = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File(temp.root, "hostkey.ser").toPath()
            )
            passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                username == "grom" && password == "s3cret-pass"
            }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        }
        sshServer.start()
        sshPort = sshServer.port

        echo = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        echoThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val client = runCatching { echo.accept() }.getOrNull() ?: return@Thread
                echoConnections.countDown()
                runCatching {
                    val request = client.getInputStream().bufferedReader().readLine()
                    client.getOutputStream().write("pong:$request\n".toByteArray())
                    client.getOutputStream().flush()
                }
                runCatching { client.close() }
            }
        }, "echo-server")
        echoThread.isDaemon = true
        echoThread.start()
    }

    @After
    fun tearDown() {
        echoThread.interrupt()
        runCatching { echo.close() }
        runCatching { sshServer.stop(true) }
    }

    private fun newStore(): SshTargetStore =
        SshTargetStore(File(temp.root, SshTargetStore.FILE_NAME), ReversingBox())

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun manager(store: SshTargetStore): SshTunnelManager =
        SshTunnelManager(
            store = store,
            audit = { _, _ -> },
            logger = { _ -> },
            localTarget = { "127.0.0.1" to echo.localPort }
        )

    @Test(timeout = 120_000)
    fun `tofu, reverse forward, end-to-end bytes and measured status`() {
        val store = newStore()
        val remotePort = freePort()
        val saved = store.upsert(
            SshTargetDraft(
                label = "test vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = remotePort
            )
        )
        val manager = manager(store)

        // 1. No pin and no trust -> the caller must confirm the fingerprint.
        val first = manager.connect(saved.id, trustHostKey = null)
        assertTrue("expected host key confirmation, got $first", first is SshTunnelManager.Result.HostKeyUnverified)
        val fingerprint = (first as SshTunnelManager.Result.HostKeyUnverified).fingerprint
        assertTrue(fingerprint.startsWith("SHA256:"))
        assertEquals(SshTunnelManager.State.DISCONNECTED, manager.state(saved.id))

        // 2. Confirm -> connected and the pin is learned.
        val second = manager.connect(saved.id, trustHostKey = fingerprint)
        assertTrue("expected connected, got $second", second is SshTunnelManager.Result.Connected)
        assertTrue((second as SshTunnelManager.Result.Connected).learned)
        assertEquals(SshTunnelManager.State.CONNECTED, manager.state(saved.id))
        assertEquals(fingerprint, store.find(saved.id)!!.hostKeySha256)

        // 3. Bytes really flow through the reverse forward.
        var response: String? = null
        runCatching {
            Socket("127.0.0.1", remotePort).use { socket ->
                socket.soTimeout = 15_000
                socket.getOutputStream().write("ping\n".toByteArray())
                socket.getOutputStream().flush()
                response = socket.getInputStream().bufferedReader().readLine()
            }
        }
        assertTrue(
            "the forwarded connection must reach the local target",
            echoConnections.await(15, TimeUnit.SECONDS)
        )
        assertEquals("pong:ping", response)

        // 4. Status is measured: no host or user in the version that may leave
        //    the device, both in the on-screen version.
        val server = manager.statusForServer()
        assertEquals(true, server["connected"])
        val tunnels = server["tunnels"] as List<*>
        val tunnel = tunnels.single() as Map<*, *>
        assertFalse("server status must not carry the host", tunnel.containsKey("host"))
        assertFalse("server status must not carry the user", tunnel.containsKey("username"))
        assertEquals("connected", tunnel["state"])

        val ui = manager.statusForUi().single()
        assertEquals("127.0.0.1", ui["host"])
        assertEquals("grom", ui["username"])

        // 5. A deliberate disconnect is state, not a failure.
        manager.disconnect(saved.id)
        assertEquals(SshTunnelManager.State.DISCONNECTED, manager.state(saved.id))
    }

    @Test(timeout = 120_000)
    fun `a changed pin is refused instead of trusted`() {
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "test vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = freePort()
            )
        )
        // Learn the real fingerprint, then replace it with a wrong pin.
        val manager = manager(store)
        val first = manager.connect(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
        store.pinHostKey(saved.id, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")

        val result = manager.connect(saved.id, null)
        assertTrue("expected a mismatch, got $result", result is SshTunnelManager.Result.HostKeyMismatch)
        assertEquals(SshTunnelManager.State.FAILED, manager.state(saved.id))
        assertFalse(manager.isActive(saved.id))
        // Even a caller-supplied trust value cannot override an existing pin.
        assertTrue(manager.connect(saved.id, first.fingerprint) is SshTunnelManager.Result.HostKeyMismatch)
    }

    @Test(timeout = 120_000)
    fun `a stopped data plane refuses to open a tunnel`() {
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "test vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = freePort()
            )
        )
        val manager = SshTunnelManager(
            store = store,
            audit = { _, _ -> },
            logger = { _ -> },
            localTarget = { null }
        )
        val result = manager.connect(saved.id, null)
        assertTrue(result is SshTunnelManager.Result.Failed)
        assertEquals(
            "DATA_PLANE_NOT_RUNNING",
            (result as SshTunnelManager.Result.Failed).code
        )
    }

    @Test(timeout = 120_000)
    fun `auto-generated keys authenticate against a public key authorizer`() {
        sshServer.publickeyAuthenticator =
            org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key, _ ->
                key != null && SshKeys.publicKeyLine(key) != null
            }

        val generated = SshKeys.generate()!!
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "key vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.KEY,
                privateKey = generated.privateKeyText,
                remoteForwardPort = freePort()
            )
        )
        val manager = manager(store)
        val probe = manager.connect(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
        val connected = manager.connect(saved.id, probe.fingerprint)
        assertTrue("expected key auth to connect, got $connected", connected is SshTunnelManager.Result.Connected)
        manager.disconnect(saved.id)
    }

    @Test(timeout = 120_000)
    fun `the active tunnel cap refuses a third live tunnel`() {
        val store = newStore()
        val manager = manager(store)
        val ids = (1..3).map { index ->
            store.upsert(
                SshTargetDraft(
                    label = "cap $index",
                    host = "127.0.0.1",
                    port = sshPort,
                    username = "grom",
                    auth = SshAuth.PASSWORD,
                    password = "s3cret-pass",
                    remoteForwardPort = freePort()
                )
            ).id
        }
        fun up(id: String) {
            val probe = manager.connect(id, null) as SshTunnelManager.Result.HostKeyUnverified
            val result = manager.connect(id, probe.fingerprint)
            assertTrue("expected connected, got $result", result is SshTunnelManager.Result.Connected)
        }
        up(ids[0])
        up(ids[1])
        assertEquals(2, manager.activeCount())

        val third = manager.connect(ids[2], null)
        assertTrue(third is SshTunnelManager.Result.Failed)
        assertEquals("TOO_MANY_TUNNELS", (third as SshTunnelManager.Result.Failed).code)
        assertEquals(2, manager.activeCount())

        manager.stopAll()
        assertEquals(0, manager.activeCount())
    }
}

class SshForwardingPolicyTest {

    private val policy = SshForwardingPolicy(localPort = 47002)

    @Test
    fun `only the local data plane port may be reached`() {
        assertTrue(
            policy.canConnect(
                org.apache.sshd.server.forward.TcpForwardingFilter.Type.Forwarded,
                org.apache.sshd.common.util.net.SshdSocketAddress("127.0.0.1", 47002),
                null
            )
        )
        assertFalse(
            policy.canConnect(
                org.apache.sshd.server.forward.TcpForwardingFilter.Type.Forwarded,
                org.apache.sshd.common.util.net.SshdSocketAddress("127.0.0.1", 22),
                null
            )
        )
        assertFalse(
            policy.canConnect(
                org.apache.sshd.server.forward.TcpForwardingFilter.Type.Forwarded,
                org.apache.sshd.common.util.net.SshdSocketAddress("10.0.0.1", 47002),
                null
            )
        )
    }

    @Test
    fun `direct channels agent and x11 are all refused`() {
        assertFalse(
            policy.canConnect(
                org.apache.sshd.server.forward.TcpForwardingFilter.Type.Direct,
                org.apache.sshd.common.util.net.SshdSocketAddress("127.0.0.1", 47002),
                null
            )
        )
        assertFalse(policy.canForwardAgent(null, "auth-agent-req@openssh.com"))
        assertFalse(policy.canForwardX11(null, "x11-req"))
        assertFalse(
            policy.canListen(
                org.apache.sshd.common.util.net.SshdSocketAddress("127.0.0.1", 47002),
                null
            )
        )
    }
}
