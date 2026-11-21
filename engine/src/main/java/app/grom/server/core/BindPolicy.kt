// SPDX-License-Identifier: Apache-2.0
package app.grom.server.core

import java.net.InetAddress

/**
 * Loopback-only bind policy (D5, S1-R010, S2-R001/S2-R002).
 *
 * The policy is deliberately an allow-list of two addresses. Anything else —
 * including the wildcard addresses, LAN addresses and hostnames — is refused
 * before a socket exists, so the server can never "fall back" to being exposed.
 */
object BindPolicy {

    const val DEFAULT_PORT: Int = 47001

    /** The only two transports Grom will ever listen on. */
    val ALLOWED: Set<String> = setOf("127.0.0.1", "::1")

    /** Recorded here so that no other file needs to mention them (test-policy). */
    private val DENIED: Set<String> = setOf("0.0.0.0", "::", "[::]", "")

    fun isAllowedLiteral(address: String): Boolean {
        val candidate = address.trim()
        if (candidate in DENIED) return false
        return candidate in ALLOWED
    }

    fun isAllowedPort(port: Int): Boolean = port in 1..65535

    fun allows(address: InetAddress?, port: Int): Boolean {
        if (address == null) return false
        if (!isAllowedPort(port)) return false
        if (address.isAnyLocalAddress) return false
        return address.isLoopbackAddress
    }

    /**
     * @throws IllegalArgumentException when the bind would not be loopback-only.
     * Callers must treat this as fatal (S2-R002); there is no degraded mode.
     */
    fun requireLoopback(address: InetAddress?, port: Int) {
        require(allows(address, port)) {
            "refusing non-loopback bind: ${address?.hostAddress}:$port"
        }
    }

    /** The address a server role binds to when the user has not chosen otherwise. */
    fun loopbackAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
}
