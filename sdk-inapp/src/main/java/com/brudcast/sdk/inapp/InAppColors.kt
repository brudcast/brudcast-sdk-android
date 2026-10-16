package com.brudcast.sdk.inapp

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

internal object InAppHexColor {
    /**
     * Parses `#RRGGBB` and `#RRGGBBAA`.
     *
     * @param hex The colour string.
     * @return The colour, or null when the string is not a colour.
     */
    fun parse(hex: String?): Color? {
        val value = hex?.trim()?.removePrefix("#") ?: return null
        if (hex.trim().firstOrNull() != '#' || (value.length != 6 && value.length != 8)) return null
        val number = value.toLongOrNull(16) ?: return null
        return if (value.length == 6) {
            Color(red = ((number shr 16) and 0xFF).toInt(), green = ((number shr 8) and 0xFF).toInt(), blue = (number and 0xFF).toInt())
        } else {
            Color(
                red = ((number shr 24) and 0xFF).toInt(),
                green = ((number shr 16) and 0xFF).toInt(),
                blue = ((number shr 8) and 0xFF).toInt(),
                alpha = (number and 0xFF).toInt(),
            )
        }
    }

    /**
     * Picks a design colour for the current appearance.
     *
     * @param light The light colour.
     * @param dark The dark colour, or null to use the light one in both.
     * @param isDark Whether the dark appearance is in use.
     * @return The colour, or null when the light colour does not parse.
     */
    fun resolve(light: String?, dark: String?, isDark: Boolean): Color? {
        val base = parse(light) ?: return null
        return if (isDark) parse(dark) ?: base else base
    }

    /**
     * The WCAG relative luminance, from 0 for black to 1 for white.
     *
     * @param color The colour.
     * @return The luminance.
     */
    fun luminance(color: Color): Double {
        fun channel(value: Float): Double = if (value <= 0.03928f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /**
     * Black or white, whichever reads better on a colour.
     *
     * @param background The colour behind the text.
     * @return Black or white.
     */
    fun contrasting(background: Color): Color = if (luminance(background) > 0.45) Color.Black else Color.White
}
