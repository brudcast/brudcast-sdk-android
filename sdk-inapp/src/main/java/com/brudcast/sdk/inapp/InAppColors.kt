package com.brudcast.sdk.inapp

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * The colours the SDK's own screens draw with: neutral white or near-black surfaces and grey ink,
 * with the accent as the only colour. Material's stock schemes tint every surface lavender, which
 * clashes with any app that is not purple.
 *
 * @param theme The accent and appearance.
 * @return The colour scheme.
 */
internal fun inAppColorScheme(theme: InAppTheme): ColorScheme {
    val onAccent = InAppHexColor.contrasting(theme.accent)
    return if (theme.isDark) {
        val surface = Color(0xFF121212)
        val raised = Color(0xFF1E1E1E)
        darkColorScheme(
            primary = theme.accent,
            onPrimary = onAccent,
            background = surface,
            onBackground = Color.White,
            surface = surface,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF2C2C2E),
            onSurfaceVariant = Color(0xFF9CA3AF),
            surfaceContainerLowest = surface,
            surfaceContainerLow = surface,
            surfaceContainer = surface,
            surfaceContainerHigh = raised,
            surfaceContainerHighest = raised,
            surfaceTint = Color.Transparent,
            outline = Color(0xFF3A3A3C),
            outlineVariant = Color(0xFF2C2C2E),
        )
    } else {
        lightColorScheme(
            primary = theme.accent,
            onPrimary = onAccent,
            background = Color.White,
            onBackground = Color(0xFF131313),
            surface = Color.White,
            onSurface = Color(0xFF131313),
            surfaceVariant = Color(0xFFF5F5F5),
            onSurfaceVariant = Color(0xFF6B7280),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = Color(0xFFF5F5F5),
            surfaceTint = Color.Transparent,
            outline = Color(0xFFD4D4D8),
            outlineVariant = Color(0xFFEAEAEC),
        )
    }
}

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

    /**
     * The WCAG contrast ratio between two colours, from 1 for the same colour to 21 for black on
     * white.
     *
     * @param first One colour.
     * @param second The other colour.
     * @return The ratio.
     */
    fun contrastRatio(first: Color, second: Color): Double {
        val lighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Picks the first theme colour that reads as a button on a surface. Theme colours are not
     * always brand colours: the platform Material theme's primary colour is its near-white app bar,
     * which would draw text buttons invisibly on a white message.
     *
     * @param candidates The theme's colours, most preferred first.
     * @param surface The colour behind the buttons.
     * @return The colour, or null when none has the 3:1 contrast WCAG asks of controls.
     */
    fun readableAccent(candidates: List<Color>, surface: Color): Color? =
        candidates.firstOrNull { contrastRatio(it, surface) >= 3.0 }
}
