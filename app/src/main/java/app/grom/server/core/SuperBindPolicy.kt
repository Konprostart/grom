// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * How the public data plane chooses its listening address (D39, S5-R006…S5-R012).
 *
 * This is deliberately separate from [BindPolicy], which stays loopback-only for
 * the control plane. A non-loopback choice here is always an explicit operator
 * decision; it is never a fallback.
 */
enum class BindMode {
    /** `127.0.0.1` only. The default and the only mode that needs no credential. */
    LOOPBACK,

    /** The device's current site-local IPv4 address, so a phone on Wi-Fi is reachable. */
    LAN,

    /** The wildcard IPv4 address (all interfaces). Requires an explicit acknowledgement. */
    PUBLIC
}

sealed class BindResolution {
    data class Bound(val address: InetAddress, val display: String) : BindResolution()
    data class Rejected(val code: String, val message: String) : BindResolution()
}

object SuperBindPolicy {

    const val DEFAULT_PORT: Int = 47002

    /**
     * Recorded here so that no other file needs to mention it; `tools/test-policy`
     * allows this literal only inside a bind-policy file.
     */
    private const val WILDCARD_V4: String = "0.0.0.0"

    private val WILDCARD_BYTES = byteArrayOf(0, 0, 0, 0)

    /**
     * Resolves a mode to a concrete address. [candidates] is injectable so the
     * selection rule is testable without touching the host's interfaces.
     */
    fun resolve(
        mode: BindMode,
        candidates: List<InetAddress> = localAddresses()
    ): BindResolution = when (mode) {
        BindMode.LOOPBACK -> BindResolution.Bound(BindPolicy.loopbackAddress(), "127.0.0.1")
        BindMode.LAN -> {
            val lan = selectLan(candidates)
            if (lan == null) {
                BindResolution.Rejected(
                    "NO_LAN_ADDRESS",
                    "no LAN address is assigned; connect to Wi-Fi or Ethernet and start again"
                )
            } else {
                BindResolution.Bound(lan, lan.hostAddress ?: "lan")
            }
        }
        BindMode.PUBLIC -> BindResolution.Bound(
            InetAddress.getByAddress(WILDCARD_BYTES),
            WILDCARD_V4
        )
    }

    /**
     * Prefers a private (site-local) IPv4 address, then any non-loopback IPv4.
     * Link-local and loopback addresses are never selected.
     */
    fun selectLan(candidates: List<InetAddress>): InetAddress? {
        val usable = candidates.filter {
            it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress
        }
        return usable.firstOrNull { it.isSiteLocalAddress }
            ?: usable.firstOrNull()
    }

    /** Enumerates the interface addresses visible to the app. */
    fun localAddresses(): List<InetAddress> {
        val result = ArrayList<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in networkInterface.inetAddresses) {
                    if (!address.isLoopbackAddress) result.add(address)
                }
            }
        } catch (_: Exception) {
            // Interface enumeration can fail on locked-down devices. An empty list
            // turns LAN mode into a truthful NO_LAN_ADDRESS, not a silent fallback.
        }
        return result
    }

    /** True only for the wildcard address; used by the start-time policy check. */
    fun isWildcard(address: InetAddress?): Boolean =
        address != null && address.isAnyLocalAddress

    /** A human label for the status document. Never includes a secret. */
    fun describe(bind: BindResolution, port: Int): String = when (bind) {
        is BindResolution.Bound -> "${bind.display}:$port"
        is BindResolution.Rejected -> "not bound (${bind.code})"
    }
}
