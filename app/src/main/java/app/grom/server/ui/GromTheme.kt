// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View

/**
 * Grom's surface is deliberately monochrome: black on white in light mode,
 * white on black in dark mode, with a single neutral surface tone and hairline
 * borders. No brand accent competes with the status, because the status is the
 * only thing the operator must be able to read at a glance.
 *
 * The mode is stored next to the other non-secret operator preferences. It
 * defaults to the system setting; the app never changes it on its own.
 */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Auto"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

data class Palette(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val onSurface: Int,
    val muted: Int,
    val border: Int,
    val accent: Int,
    val onAccent: Int,
    val positive: Int,
    val negative: Int,
    val ripple: Int
)

object GromTheme {

    private const val PREFS = "grom.ui"
    private const val KEY_MODE = "theme_mode"

    val LIGHT = Palette(
        dark = false,
        background = Color.rgb(255, 255, 255),
        surface = Color.rgb(246, 246, 246),
        onSurface = Color.rgb(10, 10, 10),
        muted = Color.rgb(112, 112, 112),
        border = Color.rgb(228, 228, 228),
        accent = Color.rgb(10, 10, 10),
        onAccent = Color.rgb(255, 255, 255),
        positive = Color.rgb(19, 122, 58),
        negative = Color.rgb(179, 38, 30),
        ripple = Color.argb(38, 0, 0, 0)
    )

    val DARK = Palette(
        dark = true,
        background = Color.rgb(0, 0, 0),
        surface = Color.rgb(17, 17, 17),
        onSurface = Color.rgb(247, 247, 247),
        muted = Color.rgb(154, 154, 154),
        border = Color.rgb(38, 38, 38),
        accent = Color.rgb(247, 247, 247),
        onAccent = Color.rgb(0, 0, 0),
        positive = Color.rgb(110, 231, 160),
        negative = Color.rgb(255, 107, 107),
        ripple = Color.argb(46, 255, 255, 255)
    )

    fun mode(context: Context): ThemeMode =
        ThemeMode.from(prefs(context).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.key).apply()
    }

    fun isDark(context: Context): Boolean = when (mode(context)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(context: Context): Palette = if (isDark(context)) DARK else LIGHT

    /**
     * Paints the window the same colour as the surface and keeps the system
     * bar icons legible. Called before `setContentView`.
     */
    fun applyWindow(activity: Activity) {
        val palette = palette(activity)
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(palette.background))

        @Suppress("DEPRECATION")
        window.statusBarColor = palette.background

        @Suppress("DEPRECATION")
        window.navigationBarColor = palette.background

        val flags = if (palette.dark) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
