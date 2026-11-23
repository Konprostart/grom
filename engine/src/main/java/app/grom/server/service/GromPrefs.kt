// SPDX-License-Identifier: Apache-2.0
package app.grom.server.service

import android.content.Context
import app.grom.server.core.BindPolicy

/**
 * Small typed wrapper over the app preferences (D14, D23, S3-R026…S3-R028).
 * Only non-secret, user-controlled settings live here; the token is in
 * [app.grom.server.store.SecureStore].
 */
object GromPrefs {

    private const val FILE = "grom"
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_PORT = "port"
    private const val KEY_QUARANTINED = "quarantined"
    private const val KEY_QUARANTINE_REASON = "quarantine_reason"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun startOnBoot(context: Context): Boolean = prefs(context).getBoolean(KEY_START_ON_BOOT, false)

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
    }

    fun port(context: Context): Int {
        val value = prefs(context).getInt(KEY_PORT, BindPolicy.DEFAULT_PORT)
        return if (BindPolicy.isAllowedPort(value)) value else BindPolicy.DEFAULT_PORT
    }

    /**
     * Reserves the loopback control port for a host that cannot use 47001.
     *
     * `port()` already honours this key, but nothing in this tree calls the
     * setter: the standalone app is the only server on its own device, so the
     * default is always free. It is kept deliberately as the seam an embedding
     * host needs once two servers can share a phone — a second app that embeds
     * the engine would otherwise collide on 47001. Wiring it into
     * `GromEmbed.Options` is a decision (D85), not an accident left unfixed.
     */
    fun setPort(context: Context, port: Int) {
        if (BindPolicy.isAllowedPort(port)) {
            prefs(context).edit().putInt(KEY_PORT, port).apply()
        }
    }

    /** Quarantine survives process death and reboot (S3-R020). */
    fun isQuarantined(context: Context): Boolean = prefs(context).getBoolean(KEY_QUARANTINED, false)

    fun quarantineReason(context: Context): String? =
        prefs(context).getString(KEY_QUARANTINE_REASON, null)

    fun setQuarantined(context: Context, reason: String?) {
        prefs(context).edit()
            .putBoolean(KEY_QUARANTINED, reason != null)
            .putString(KEY_QUARANTINE_REASON, reason)
            .apply()
    }
}
