package com.brudcast.sample.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.brudcast.sample.R

/**
 * The sample's colours that have no Material slot: brand ink, the third ink tier, the two rule
 * weights, navigation fills and status.
 */
class BrudcastTokens(
    val brand: Color,
    val brandSubtle: Color,
    val foregroundSubtle: Color,
    val border: Color,
    val borderStrong: Color,
    val inputActive: Color,
    val navActive: Color,
    val success: Color,
    val successForeground: Color,
    val warning: Color,
    val warningForeground: Color,
    val destructive: Color,
    val destructiveForeground: Color,
)

private val LightTokens = BrudcastTokens(
    brand = Color(0xFF365AED),
    brandSubtle = Color(0x0F365AED),
    foregroundSubtle = Color(0xFF8A8A8F),
    border = Color(0xFFEAEAEC),
    borderStrong = Color(0xFFD4D4D8),
    inputActive = Color(0xFFD4D4D4),
    navActive = Color(0x0F131313),
    success = Color(0xFFF0FDF4),
    successForeground = Color(0xFF16A34A),
    warning = Color(0xFFFFFBEB),
    warningForeground = Color(0xFFF59E0B),
    destructive = Color(0xFFFEF2F2),
    destructiveForeground = Color(0xFFEF4444),
)

private val DarkTokens = BrudcastTokens(
    brand = Color(0xFF889EFF),
    brandSubtle = Color(0x14889EFF),
    foregroundSubtle = Color(0xFF6E6E73),
    border = Color(0xFF262626),
    borderStrong = Color(0xFF343437),
    inputActive = Color(0xCC262626),
    navActive = Color(0x14FFFFFF),
    success = Color(0x2616A34A),
    successForeground = Color(0xFF4ADE80),
    warning = Color(0x26F59E0B),
    warningForeground = Color(0xFFFBBF24),
    destructive = Color(0x26EF4444),
    destructiveForeground = Color(0xFFF87171),
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF365AED),
    onPrimary = Color.White,
    primaryContainer = LightTokens.brandSubtle,
    onPrimaryContainer = LightTokens.brand,
    secondaryContainer = LightTokens.navActive,
    onSecondaryContainer = Color(0xFF131313),
    background = Color.White,
    onBackground = Color(0xFF131313),
    surface = Color.White,
    onSurface = Color(0xFF131313),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF5F5F5),
    outline = LightTokens.borderStrong,
    outlineVariant = LightTokens.border,
    error = LightTokens.destructiveForeground,
    errorContainer = LightTokens.destructive,
    scrim = Color(0x4D000000),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF3151D5),
    onPrimary = Color.White,
    primaryContainer = DarkTokens.brandSubtle,
    onPrimaryContainer = DarkTokens.brand,
    secondaryContainer = DarkTokens.navActive,
    onSecondaryContainer = Color.White,
    background = Color(0xFF131313),
    onBackground = Color.White,
    surface = Color(0xFF131313),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF313131),
    onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceContainerLowest = Color(0xFF0F0F0F),
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF313131),
    outline = DarkTokens.borderStrong,
    outlineVariant = DarkTokens.border,
    error = DarkTokens.destructiveForeground,
    errorContainer = DarkTokens.destructive,
    scrim = Color(0x4D000000),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(resource: Int, weight: Int): Font =
    Font(resource, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Hanken Grotesk, the body face, with 450 as the body weight. */
val HankenGrotesk = FontFamily(listOf(400, 450, 500, 600, 700).map { variable(R.font.hanken_grotesk, it) })

/** Gabarito, the display face for headings and metric numerals. */
val Gabarito = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.gabarito, it) })

private val Tight = (-0.025).em

/**
 * The sample's type scale on Material's slots: page titles at 24 medium, section headings at
 * 16 normal and overlay titles at 18 normal in Gabarito, body at 14/450 and labels at 13 medium in
 * Hanken Grotesk, metric numerals at 24 semibold in Gabarito.
 */
private val BrudcastTypography = Typography(
    displaySmall = TextStyle(fontFamily = Gabarito, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = Tight, fontFeatureSettings = "tnum"),
    headlineSmall = TextStyle(fontFamily = Gabarito, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = Tight),
    titleLarge = TextStyle(fontFamily = Gabarito, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = Tight),
    titleMedium = TextStyle(fontFamily = Gabarito, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = Tight),
    titleSmall = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight(450), fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight(450), fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

/** The sample's radius scale: 6, 8 (buttons), 10 (inputs), 14 (boxed panels), 16. */
private val BrudcastShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

private val LocalBrudcastTokens = staticCompositionLocalOf { LightTokens }

/** The sample's colours Material has no slot for. */
val MaterialTheme.tokens: BrudcastTokens
    @Composable get() = LocalBrudcastTokens.current

/**
 * The sample's theme, following the device's light or dark setting.
 *
 * @param content The app.
 */
@Composable
fun BrudcastTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalBrudcastTokens provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = BrudcastTypography,
            shapes = BrudcastShapes,
            content = content,
        )
    }
}
