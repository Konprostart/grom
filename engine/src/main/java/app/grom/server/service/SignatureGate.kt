// SPDX-License-Identifier: Apache-2.0
package app.grom.server.service

import android.content.pm.PackageManager

/**
 * Layer 2 of the IPC gate (D15, S4-R007…S4-R010).
 *
 * Comparing signatures by UID avoids resolving the caller's package name, which
 * package-visibility rules on API 30+ could otherwise interfere with.
 */
object SignatureGate {

    fun isSameSigner(packageManager: PackageManager, myUid: Int, callingUid: Int): Boolean {
        if (myUid == callingUid) return true
        return try {
            packageManager.checkSignatures(myUid, callingUid) == PackageManager.SIGNATURE_MATCH
        } catch (_: Exception) {
            false
        }
    }
}
