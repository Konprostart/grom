// SPDX-License-Identifier: Apache-2.0
package app.grom.server.embed

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The screen behind each advice action (D90).
 *
 * A wrong intent is worse than no button: it teaches the operator that the
 * advice is noise. Two of these are easy to confuse — the battery-optimisation
 * *list* and the one-tap exemption dialog are different actions — so the mapping
 * is asserted rather than assumed.
 */
class EmbedSettingsTest {

    @Test
    fun `battery advice opens the exemption list`() {
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            EmbedSettings.actionFor(EmbedAction.OPEN_BATTERY_SETTINGS)
        )
    }

    @Test
    fun `notification advice opens this app's notification page`() {
        assertEquals(
            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            EmbedSettings.actionFor(EmbedAction.OPEN_NOTIFICATION_SETTINGS)
        )
    }

    @Test
    fun `app advice opens this app's own page`() {
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            EmbedSettings.actionFor(EmbedAction.OPEN_APP_SETTINGS)
        )
    }

    @Test
    fun `the three settings pages are distinct`() {
        val pages = listOf(
            EmbedAction.OPEN_BATTERY_SETTINGS,
            EmbedAction.OPEN_NOTIFICATION_SETTINGS,
            EmbedAction.OPEN_APP_SETTINGS
        ).map { EmbedSettings.actionFor(it) }

        assertEquals(pages.size, pages.distinct().size)
    }

    @Test
    fun `actions the host draws itself have no platform screen`() {
        assertNull(EmbedSettings.actionFor(EmbedAction.NONE))
        assertNull(EmbedSettings.actionFor(EmbedAction.GRANT_CONSENT))
        assertNull(EmbedSettings.actionFor(EmbedAction.RESTART_SERVER))
        assertNull(EmbedSettings.actionFor(EmbedAction.RETRY))
    }

    @Test
    fun `the vocabulary is the one the guide documents`() {
        // A new EmbedAction must be added deliberately: falling through to "no
        // screen" for a settings action would hide a button the advice promised,
        // and a new one would reach a host that does not know it.
        assertEquals(7, EmbedAction.entries.size)
        assertEquals(
            setOf(
                EmbedAction.OPEN_BATTERY_SETTINGS,
                EmbedAction.OPEN_NOTIFICATION_SETTINGS,
                EmbedAction.OPEN_APP_SETTINGS
            ),
            EmbedAction.entries.filter { EmbedSettings.actionFor(it) != null }.toSet()
        )
    }
}
