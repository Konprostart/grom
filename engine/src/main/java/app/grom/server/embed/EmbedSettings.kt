// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The platform screen behind an [EmbedAction] (D90).
 *
 * An advice item is only useful if the button under it opens the right page.
 * Every host would otherwise hard-code this mapping, and the subtle ones are
 * easy to get wrong: the battery list and Android's one-tap exemption dialog are
 * different intents, and the notification page needs the package as an extra.
 *
 * The mapping is split in two on purpose: [actionFor] returns the platform
 * action string and is pure (a test asserts it), while [intentFor] builds the
 * `Intent` and is six lines of glue with no decisions in it.
 *
 * Nothing here changes a setting. Every intent is one of Android's own screens,
 * opened for the operator to decide (D80, D82).
 */
object EmbedSettings {

    /**
     * The platform action for [action], or `null` when no settings screen fixes
     * it (consent, a restart, a retry — the host draws those itself).
     */
    fun actionFor(action: EmbedAction): String? = when (action) {
        // The *list* of apps exempt from optimisation: no permission needed, and
        // the operator cannot accidentally answer for the wrong app.
        EmbedAction.OPEN_BATTERY_SETTINGS -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS

        EmbedAction.OPEN_NOTIFICATION_SETTINGS -> Settings.ACTION_APP_NOTIFICATION_SETTINGS

        EmbedAction.OPEN_APP_SETTINGS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS

        EmbedAction.NONE,
        EmbedAction.GRANT_CONSENT,
        EmbedAction.RESTART_SERVER,
        EmbedAction.RETRY -> null
    }

    /**
     * An `Intent` that opens the screen for [action], or `null` when there is
     * none. The caller must still handle `ActivityNotFoundException`: vendor
     * ROMs do remove pages.
     */
    fun intentFor(context: Context, action: EmbedAction): Intent? {
        val platformAction = actionFor(action) ?: return null
        val intent = Intent(platformAction)
        when (action) {
            EmbedAction.OPEN_NOTIFICATION_SETTINGS -> {
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            EmbedAction.OPEN_APP_SETTINGS -> {
                intent.data = Uri.parse("package:${context.packageName}")
            }
            else -> Unit
        }
        return intent
    }
}
