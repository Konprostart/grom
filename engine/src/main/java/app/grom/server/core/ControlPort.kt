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

    /** A port change cannot move a listener that is already serving (S4-R051). */
    const val RESTART_REQUIRED: String = "PORT_CHANGE_RESTART_REQUIRED"

    /**
     * The sentinel a host passes when it does not want to think about ports
     * (D89). It is never a real port: [resolve] replaces it with a free one from
     * [AUTO_RANGE] *before* the service starts, and the choice is returned.
     */
    const val AUTO_PORT: Int = 0

    /**
     * Where an automatic choice looks. Deliberately away from the standalone
     * app's 47001 and from the public data plane's 47002, so an embedding host
     * and an installed Grom cannot collide by default.
     */
    val AUTO_RANGE: IntRange = 47100..47199

    /**
     * Which port the server should serve, as a decision rather than an attempt
     * (D85, D89).
     *
     * @param requested what the host asked for: `null` keeps the stored port,
     *   [AUTO_PORT] asks for the first free port from [AUTO_RANGE], anything
     *   else is a specific choice.
     * @param stored the port persisted from a previous run.
     * @param serving the port this process is already serving, or `-1`.
     * @param isFree the probe; injectable so the matrix is testable without a
     *   socket (D30).
     */
    fun resolve(
        requested: Int?,
        stored: Int,
        serving: Int,
        isFree: (Int) -> Boolean
    ): Choice {
        // A listener that is already up cannot be moved without a restart, and
        // pretending otherwise would break every client holding the old port.
        if (serving > 0) {
            return when {
                requested == null || requested == AUTO_PORT || requested == serving -> Choice(serving)
                !BindPolicy.isAllowedPort(requested) -> Choice(serving, NOT_ALLOWED)
                else -> Choice(serving, RESTART_REQUIRED)
            }
        }
        if (requested != null && requested != AUTO_PORT) {
            if (!BindPolicy.isAllowedPort(requested)) return Choice(requested, NOT_ALLOWED)
            if (!isFree(requested)) return Choice(requested, PORT_IN_USE)
            return Choice(requested)
        }
        if (requested == AUTO_PORT) {
            // Reuse the stored choice when it is still usable: the hub's tunnel
            // target, a paired device and the host's own bookmarks all remember
            // a port, so an automatic choice must be stable between restarts.
            if (BindPolicy.isAllowedPort(stored) &&
                stored != BindPolicy.DEFAULT_PORT &&
                isFree(stored)
            ) {
                return Choice(stored)
            }
            val picked = AUTO_RANGE.firstOrNull { isFree(it) }
            return if (picked != null) Choice(picked) else Choice(stored, PORT_IN_USE)
        }
        if (!BindPolicy.isAllowedPort(stored)) return Choice(stored, NOT_ALLOWED)
        if (!isFree(stored)) return Choice(stored, PORT_IN_USE)
        return Choice(stored)
    }

    /** A port and the stable reason it cannot be used, `null` when it can. */
    data class Choice(val port: Int, val refusal: String? = null) {
        val usable: Boolean get() = refusal == null
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
