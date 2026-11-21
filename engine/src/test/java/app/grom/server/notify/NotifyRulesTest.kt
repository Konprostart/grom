// SPDX-License-Identifier: Apache-2.0
package app.grom.server.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event table and the audit-to-message mapping (D65).
 *
 * This is the file that decides what may leave the device, so it asserts both
 * directions: every mapped action produces exactly one of the fixed sentences,
 * and nothing from the audit `detail` reaches the message except a well-formed
 * code or a valid project id.
 */
class NotifyRulesTest {

    private fun event(action: String, detail: String = ""): NotifyEvent? =
        NotifyRules.classify(action, detail)?.event

    @Test
    fun `lifecycle actions map to their events`() {
        assertEquals(NotifyEvent.SERVER_STARTED, event("server.start", "port=47001"))
        assertEquals(NotifyEvent.SERVER_STOPPED, event("server.stop"))
        assertEquals(NotifyEvent.AGENT_READY, event("agent.ready", "pid=123"))
        assertEquals(NotifyEvent.AGENT_CRASHED, event("agent.crash", "cause=exit crashes=1 quarantined=false"))
    }

    @Test
    fun `a crash that quarantines the agent is one message, not two`() {
        val message = NotifyRules.classify(
            "agent.crash",
            "cause=launch failed crashes=3 quarantined=true"
        )
        assertEquals(NotifyEvent.AGENT_QUARANTINED, message?.event)
        assertTrue(message!!.text().contains("quarantined"))
        assertFalse("must not also claim a restart", message.text().contains("restarting"))
        // The same outcome from the fatal path collapses onto the same code, so
        // coalescing turns the pair into a single notification.
        assertEquals(NotifyEvent.AGENT_QUARANTINED, event("agent.fatal", "boom"))
    }

    @Test
    fun `data plane transitions map`() {
        assertEquals(NotifyEvent.DATA_PLANE_STARTED, event("super.start", "address=127.0.0.1:47002"))
        assertEquals(NotifyEvent.DATA_PLANE_STOPPED, event("super.stop"))
        assertEquals(NotifyEvent.DATA_PLANE_REFUSED, event("super.refused", "CREDENTIAL_REQUIRED"))
        assertEquals(NotifyEvent.DATA_PLANE_BIND_FAILED, event("super.bind.failed", "BindException"))
    }

    @Test
    fun `bridge transitions map, and a deliberate disconnect does not`() {
        assertEquals(NotifyEvent.BRIDGE_CONNECTED, event("ssh.connect", "target=t1 host=10.0.0.1:22"))
        assertEquals(NotifyEvent.BRIDGE_DISCONNECTED, event("ssh.closed", "target=t1"))
        assertEquals(NotifyEvent.BRIDGE_HOSTKEY_MISMATCH, event("ssh.hostkey.mismatch", "target=t1"))
        assertNull("an operator disconnect is not an event", event("ssh.disconnect", "target=t1"))
        assertNull("first-use handshakes are not events", event("ssh.hostkey.learned", "target=t1"))
        assertNull(event("ssh.hostkey.unverified", "target=t1"))
    }

    @Test
    fun `a failure carries its stable code but never the host`() {
        val message = NotifyRules.classify(
            "ssh.failed",
            "target=t1 code=SSH_TIMEOUT"
        )
        assertEquals(NotifyEvent.BRIDGE_FAILED, message?.event)
        assertEquals("SSH_TIMEOUT", message?.subject)
        val text = message!!.text()
        assertTrue(text.contains("SSH_TIMEOUT"))
        assertFalse("the audit detail's target must not leak", text.contains("t1"))
    }

    @Test
    fun `a malformed code is dropped instead of forwarded`() {
        // The event still happens; only the code is refused, so the message
        // says "connection failed" without quoting anything unrecognised.
        val lowercase = NotifyRules.classify("ssh.failed", "target=t1 code=not a code")
        assertEquals(NotifyEvent.BRIDGE_FAILED, lowercase?.event)
        assertNull(lowercase?.subject)

        val missing = NotifyRules.classify("ssh.failed", "target=t1")
        assertEquals(NotifyEvent.BRIDGE_FAILED, missing?.event)
        assertNull(missing?.subject)

        val injected = NotifyRules.classify("ssh.failed", "code=<script>")
        assertEquals(NotifyEvent.BRIDGE_FAILED, injected?.event)
        assertNull("an injected fragment is not a code", injected?.subject)

        val refused = NotifyRules.classify("super.refused", "")
        assertEquals(NotifyEvent.DATA_PLANE_REFUSED, refused?.event)
        assertNull(refused?.subject)
    }

    @Test
    fun `project events carry a validated id only`() {
        val registered = NotifyRules.classify("project.announce", "id=my-wallet version=1.0")
        assertEquals(NotifyEvent.PROJECT_REGISTERED, registered?.event)
        assertEquals("my-wallet", registered?.subject)

        val removed = NotifyRules.classify("project.remove", "id=my-wallet")
        assertEquals(NotifyEvent.PROJECT_REMOVED, removed?.event)

        assertNull(
            "a path-like id must not be forwarded",
            NotifyRules.classify("project.announce", "id=../../etc/passwd")?.subject
        )
        assertNull(NotifyRules.classify("project.announce", "id=UPPER")?.subject)
    }

    @Test
    fun `an unreachable Lightning backend is an event, a healthy one is not`() {
        assertEquals(NotifyEvent.LIGHTNING_UNREACHABLE, event("lightning.probe", "reachable=false"))
        assertNull(event("lightning.probe", "reachable=true"))
    }

    @Test
    fun `unknown actions are ignored`() {
        listOf(
            "token.rotate",
            "agent.exec",
            "ssh.target.save",
            "notify.save",
            "notify.test",
            "super.reload.reference",
            "audit.verify",
            ""
        ).forEach { action ->
            assertNull("action '$action' must not notify", event(action, "anything"))
        }
    }

    @Test
    fun `every message starts with the product name and stays short`() {
        NotifyEvent.entries.forEach { event ->
            val text = event.render("SOME_CODE")
            assertTrue(text.startsWith("Grom: "))
            assertTrue(text.endsWith("."))
            assertTrue(text.length <= NotifyEvent.MAX_MESSAGE_CHARS)
        }
    }

    @Test
    fun `a message never carries a URL, a token shape or an amount`() {
        NotifyEvent.entries.forEach { event ->
            val text = event.render(null).lowercase()
            listOf("http", "www.", "@", "/", "\\", "123456789:", "btc", "sats", "$").forEach {
                assertFalse("${event.code} leaked '$it'", text.contains(it))
            }
        }
    }
}
