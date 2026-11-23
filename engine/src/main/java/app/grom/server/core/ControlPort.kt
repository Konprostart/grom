// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Is the loopback control port free? (D85, S4-R051)
 *
 * A phone can hold more than one Grom now: the standalone server and any host
 * application that embeds `:engine` are separate packages, so they have separate
 * data, separate identities and — unless the host chooses otherwise — the same
 * default control port. The second server would fail to bind, and before this
 * existed the only trace was a generic "refused to start", which sends the
 * operator looking for a signature problem that does not exist.
 *
 * This is a *probe*, not a reservation: it binds and releases the loopback
 * address immediately. The authoritative answer is still the real bind in
 * [app.grom.server.http.LoopbackHttpServer.start] — a probe can only ever make
 * the failure arrive earlier and with a name.
 */
object ControlPort {

    /** Stable code reported when the chosen control port is taken (S4-R051). */
    const val PORT_IN_USE: String = "PORT_IN_USE"

    /** Stable code for a port the bind policy would refuse before any socket exists. */
    const val NOT_ALLOWED: String = "BAD_CONTROL_PORT"

    /**
     * The stable code that stops a start, or `null` when [port] can be used.
     *
     * This is the decision a host needs *before* it starts the service, so the
     * failure is a returned code rather than a process that dies later.
     */
    fun refusal(port: Int): String? = when {
        !BindPolicy.isAllowedPort(port) -> NOT_ALLOWED
        !isFree(port) -> PORT_IN_USE
        else -> null
    }

    /**
     * True when nothing holds [port] on loopback right now.
     *
     * A port outside `1..65535` is never "free": the bind policy would refuse it
     * anyway, and reporting it as available would send the caller to a start
     * that cannot succeed.
     */
    fun isFree(port: Int): Boolean {
        if (!BindPolicy.isAllowedPort(port)) return false
        val address = BindPolicy.loopbackAddress()
        if (!BindPolicy.allows(address, port)) return false
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(address, port), 1)
            }
            true
        } catch (_: BindException) {
            // The address is definitely held: this is the answer callers want.
            false
        } catch (_: Exception) {
            // The probe itself could not run (for example a main-thread socket
            // policy). That is not evidence the port is taken, and the real bind
            // in LoopbackHttpServer is authoritative, so do not invent a conflict.
            true
        }
    }
}
