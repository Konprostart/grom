// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import app.grom.server.http.GromApi
import app.grom.server.http.HttpResponse
import app.grom.server.http.LoopbackHttpServer
import app.grom.server.http.Router
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.ServerSocket

/**
 * Coexistence of two Groms on one phone (D85, S4-R051).
 *
 * The standalone app and a host application that embeds `:engine` are separate
 * packages with separate data and identities, but one loopback port. These
 * tests pin the three facts the operator depends on:
 *
 *   1. the probe agrees with a real bind;
 *   2. a taken port produces a stable code, not a generic failure;
 *   3. two listeners cannot share the port — the second one is refused.
 */
class ControlPortTest {

    private val listeners = mutableListOf<LoopbackHttpServer>()

    @After
    fun tearDown() {
        listeners.forEach { runCatching { it.stop() } }
        listeners.clear()
    }

    @Test
    fun `a free port is free and produces no refusal`() {
        val port = freePort()

        assertTrue(ControlPort.isFree(port))
        assertNull(ControlPort.refusal(port))
    }

    @Test
    fun `a held port is not free and produces PORT_IN_USE`() {
        val socket = ServerSocket(0, 1, BindPolicy.loopbackAddress())
        try {
            val port = socket.localPort

            assertFalse(ControlPort.isFree(port))
            assertEquals(ControlPort.PORT_IN_USE, ControlPort.refusal(port))
        } finally {
            socket.close()
        }
    }

    @Test
    fun `ports the bind policy would refuse are refused before any socket exists`() {
        assertEquals(ControlPort.NOT_ALLOWED, ControlPort.refusal(0))
        assertEquals(ControlPort.NOT_ALLOWED, ControlPort.refusal(-1))
        assertEquals(ControlPort.NOT_ALLOWED, ControlPort.refusal(70_000))
        assertFalse(ControlPort.isFree(0))
    }

    @Test
    fun `two servers cannot share one control port`() {
        val port = freePort()
        val first = LoopbackHttpServer(port, Router(FakeApi()))
        first.start()
        listeners += first

        assertFalse("the running listener must make the probe say so", ControlPort.isFree(port))

        val second = LoopbackHttpServer(port, Router(FakeApi()))
        val refused = runCatching { second.start() }.exceptionOrNull()

        assertTrue(
            "expected a bind conflict, got ${refused?.javaClass?.simpleName}",
            refused is BindException
        )
        // The first server still holds its port: the refusal changed nothing.
        assertFalse(ControlPort.isFree(port))
    }

    @Test
    fun `a distinct port lets the second server start`() {
        val port = freePort()
        val first = LoopbackHttpServer(port, Router(FakeApi()))
        first.start()
        listeners += first

        val other = freePort()
        assertNull(ControlPort.refusal(other))
        val second = LoopbackHttpServer(other, Router(FakeApi()))
        second.start()
        listeners += second

        // Both are listening: this is the coexistence D85 makes possible.
        assertFalse(ControlPort.isFree(port))
        assertFalse(ControlPort.isFree(other))
    }

    /** A port the OS just handed out is free by definition once released. */
    private fun freePort(): Int = ServerSocket(0, 1, BindPolicy.loopbackAddress()).use { it.localPort }

    private class FakeApi : GromApi {
        override fun apiVersion(): Int = 1
        override fun authorize(candidate: String?): Boolean = false
        override fun health(): Map<String, Any?> = mapOf("api" to 1, "state" to "READY")
        override fun status(): Map<String, Any?> = mapOf("state" to "READY")
        override fun auditTail(limit: Int): Map<String, Any?> = mapOf("entries" to emptyList<Any?>())
        override fun auditVerify(): Map<String, Any?> = mapOf("ok" to true)
        override fun rotateToken(): String = "rotated"
        override fun startAgent(): Map<String, Any?> = mapOf("state" to "STARTING")
        override fun stopAgent(): Map<String, Any?> = mapOf("state" to "STOPPED")
        override fun restartAgent(): Map<String, Any?> = mapOf("state" to "STARTING")
        override fun resetAgent(): Map<String, Any?> = mapOf("state" to "STOPPED")
        override fun exec(op: String, argsJson: String?): HttpResponse =
            HttpResponse(200, """{"op":"$op","ok":true}""")
    }
}
