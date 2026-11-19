// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Small view factory for the operator surface. Keeping it here means the
 * activity reads as layout, and every card/button in the app has the same
 * corner radius, border and type scale.
 *
 * Tokens: 20dp cards, 14dp pills, 11sp tracked labels, 15sp body, 34sp status.
 */
internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.text(
    value: String,
    sizeSp: Float,
    color: Int,
    style: Int = Typeface.NORMAL,
    letterSpacing: Float = 0f,
    gravity: Int = Gravity.START
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = Typeface.create("sans-serif", style)
    this.letterSpacing = letterSpacing
    this.gravity = gravity
    includeFontPadding = false
}

internal fun Context.label(value: String, palette: Palette): TextView =
    text(value.uppercase(Locale.ROOT), 11f, palette.muted, Typeface.BOLD, letterSpacing = 0.14f)

internal fun Context.shape(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

internal fun Context.pill(
    value: String,
    textColor: Int,
    fill: Int,
    rippleColor: Int,
    stroke: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
): TextView = text(
    value = value,
    sizeSp = 14f,
    color = textColor,
    style = Typeface.BOLD,
    letterSpacing = 0.02f,
    gravity = Gravity.CENTER
).apply {
    setPadding(dp(18), dp(14), dp(18), dp(14))
    this.background = RippleDrawable(
        ColorStateList.valueOf(rippleColor),
        shape(fill, 999, stroke),
        null
    )
    isClickable = enabled
    isFocusable = enabled
    alpha = if (enabled) 1f else 0.45f
    setOnClickListener { if (enabled) onClick() }
}

internal fun Context.card(palette: Palette, radiusDp: Int = 20): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(palette.surface, radiusDp, palette.border)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

internal fun Context.actionRow(
    title: String,
    value: String?,
    palette: Palette,
    onClick: () -> Unit
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(13), 0, dp(13))
    isClickable = true
    isFocusable = true
    background = RippleDrawable(
        ColorStateList.valueOf(palette.ripple),
        null,
        null
    )
    setOnClickListener { onClick() }
    addView(
        text(title, 15f, palette.onSurface),
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    )
    if (!value.isNullOrEmpty()) {
        addView(text(value, 13f, palette.muted, gravity = Gravity.END))
    }
    addView(
        text("\u203A", 20f, palette.muted, gravity = Gravity.END).apply {
            setPadding(dp(10), 0, 0, 0)
        }
    )
}

internal fun Context.divider(palette: Palette): View = View(this).apply {
    setBackgroundColor(palette.border)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(1)
    )
}

internal fun Context.gap(heightDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
}
