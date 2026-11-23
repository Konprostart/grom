// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stable code a host sees when the platform refuses a start (D90).
 *
 * `ForegroundServiceStartNotAllowedException` is the one refusal an operator can
 * fix by opening the app, and it used to be flattened into
 * `START_REFUSED: ForegroundServiceStartNotAllowedException` — which reads like a
 * crash and gives the host nothing to switch on. It gets its own code so a host
 * can say "open the app and start the server there" instead.
 */
class GromEmbedStartCodeTest {

    /** Same simple name as the platform class, without needing Android at test time. */
    private class ForegroundServiceStartNotAllowedException : RuntimeException()

    @Test
    fun `a background start refusal has its own code`() {
        assertEquals(
            EmbedHealth.FOREGROUND_START_NOT_ALLOWED,
            GromEmbed.startFailureCode(ForegroundServiceStartNotAllowedException())
        )
    }

    @Test
    fun `anything else is a named refusal, never a bare success`() {
        assertEquals(
            EmbedHealth.START_REFUSED,
            GromEmbed.startFailureCode(IllegalStateException("boom"))
        )
        assertEquals(
            EmbedHealth.START_REFUSED,
            GromEmbed.startFailureCode(SecurityException("no"))
        )
    }

    @Test
    fun `the sentinel is not a usable port`() {
        assertEquals(0, GromEmbed.AUTO_PORT)
        assertEquals(ControlPortRestartAlias.VALUE, GromEmbed.PORT_CHANGE_RESTART_REQUIRED)
    }

    /** Keeps the alias assertion readable without importing the engine's core package twice. */
    private object ControlPortRestartAlias {
        const val VALUE: String = "PORT_CHANGE_RESTART_REQUIRED"
    }
}
