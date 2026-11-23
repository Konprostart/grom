// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The port decision a host makes before it starts anything (D85, D89).
 *
 * `resolve` is the whole matrix as a pure function with an injected probe, so
 * every branch is decided here rather than on a real phone. The three rules the
 * operator depends on:
 *
 *  1. a running listener never moves — the answer is a restart, not a silent
 *     relocation that breaks the hub's tunnel target;
 *  2. an explicit port is used exactly, and a taken one is named;
 *  3. an automatic choice (`AUTO_PORT`) is stable between runs, and never picks
 *     the standalone app's default port.
 */
class ControlPortResolveTest {

    /** Every port is free unless a test says otherwise. */
    private val free: (Int) -> Boolean = { true }

    /** The documented automatic range, and nothing else. */
    private val freeInAutoRange: (Int) -> Boolean = { it in ControlPort.AUTO_RANGE }

    @Test
    fun `an explicit free port is used as asked`() {
        val choice = ControlPort.resolve(47_101, stored = 47_001, serving = -1, isFree = free)
        assertEquals(47_101, choice.port)
        assertNull(choice.refusal)
        assertTrue(choice.usable)
    }

    @Test
    fun `an explicit taken port is named, not silently replaced`() {
        val choice = ControlPort.resolve(47_101, stored = 47_001, serving = -1, isFree = { false })
        assertEquals(ControlPort.PORT_IN_USE, choice.refusal)
        assertEquals(47_101, choice.port)
    }

    @Test
    fun `an explicit port outside the range is refused before any socket`() {
        assertEquals(
            ControlPort.NOT_ALLOWED,
            ControlPort.resolve(-1, stored = 47_001, serving = -1, isFree = free).refusal
        )
        assertEquals(
            ControlPort.NOT_ALLOWED,
            ControlPort.resolve(70_000, stored = 47_001, serving = -1, isFree = free).refusal
        )
    }

    @Test
    fun `zero is the auto sentinel, not an invalid port`() {
        val choice = ControlPort.resolve(0, stored = 47_001, serving = -1, isFree = free)
        assertEquals(ControlPort.AUTO_RANGE.first, choice.port)
        assertNull(choice.refusal)
    }

    @Test
    fun `no request keeps the stored port when it is free`() {
        val choice = ControlPort.resolve(null, stored = 47_001, serving = -1, isFree = free)
        assertEquals(47_001, choice.port)
        assertNull(choice.refusal)
    }

    @Test
    fun `no request refuses when the stored port is taken`() {
        val choice = ControlPort.resolve(null, stored = 47_001, serving = -1, isFree = { false })
        assertEquals(ControlPort.PORT_IN_USE, choice.refusal)
    }

    @Test
    fun `auto picks the first free port in the documented range`() {
        val choice = ControlPort.resolve(
            requested = ControlPort.AUTO_PORT,
            stored = 47_001,
            serving = -1,
            isFree = { it >= 47_102 }
        )
        assertEquals(47_102, choice.port)
        assertNull(choice.refusal)
    }

    @Test
    fun `auto reuses a stored choice so the tunnel target keeps working`() {
        val choice = ControlPort.resolve(
            requested = ControlPort.AUTO_PORT,
            stored = 47_103,
            serving = -1,
            isFree = { true }
        )
        assertEquals(47_103, choice.port)
    }

    @Test
    fun `auto never reuses the standalone default port`() {
        val choice = ControlPort.resolve(
            requested = ControlPort.AUTO_PORT,
            stored = BindPolicy.DEFAULT_PORT,
            serving = -1,
            isFree = freeInAutoRange
        )
        assertEquals(ControlPort.AUTO_RANGE.first, choice.port)
        assertTrue("the standalone port collides by design", choice.port != BindPolicy.DEFAULT_PORT)
    }

    @Test
    fun `auto reports PORT_IN_USE when the whole range is taken`() {
        val choice = ControlPort.resolve(
            requested = ControlPort.AUTO_PORT,
            stored = 47_001,
            serving = -1,
            isFree = { false }
        )
        assertEquals(ControlPort.PORT_IN_USE, choice.refusal)
    }

    @Test
    fun `a serving listener is never moved`() {
        val same = ControlPort.resolve(47_101, stored = 47_101, serving = 47_101, isFree = { false })
        assertEquals(47_101, same.port)
        assertNull("our own listener is not a conflict", same.refusal)

        val auto = ControlPort.resolve(
            ControlPort.AUTO_PORT, stored = 47_101, serving = 47_101, isFree = { false }
        )
        assertEquals("an automatic choice must not relocate a running server", 47_101, auto.port)
        assertNull(auto.refusal)

        val keep = ControlPort.resolve(null, stored = 47_101, serving = 47_101, isFree = { false })
        assertEquals(47_101, keep.port)
        assertNull(keep.refusal)
    }

    @Test
    fun `changing the port while serving needs a restart, not a lie`() {
        val choice = ControlPort.resolve(47_102, stored = 47_101, serving = 47_101, isFree = free)
        assertEquals(ControlPort.RESTART_REQUIRED, choice.refusal)
        assertEquals(47_101, choice.port)
    }

    @Test
    fun `a values-refused port while serving is still named as invalid`() {
        val choice = ControlPort.resolve(70_000, stored = 47_101, serving = 47_101, isFree = free)
        assertEquals(ControlPort.NOT_ALLOWED, choice.refusal)
    }
}
