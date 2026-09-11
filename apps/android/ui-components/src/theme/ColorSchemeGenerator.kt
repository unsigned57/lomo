package com.lomo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.lomo.domain.model.ColorPresetId
import com.lomo.domain.model.ColorSource

/**
 * Derives a complete Material 3 [ColorScheme] from a single ARGB seed using a tone-based palette
 * mapping. Pure function — no Compose runtime, no Android Context, no platform calls.
 *
 * The algorithm is intentionally simpler than Google's HCT-based Material You pipeline:
 * - The seed colour fixes the primary hue.
 * - Secondary and tertiary roles rotate the hue and dampen saturation.
 * - Each M3 token maps to a (hue, saturation, tone) triple resolved by [paletteToneToColor].
 *
 * This is the single seed → [ColorScheme] pipeline. The legacy hand-picked
 * `LightColorScheme`/`DarkColorScheme` constants are gone; every preset / custom-seed / wallpaper
 * fallback now flows through here.
 */
fun colorSchemeFromSeed(
    seedArgb: Int,
    isDark: Boolean,
): ColorScheme {
    val (primaryHue, primarySat) = extractHueAndSaturation(seedArgb)
    val secondaryHue = primaryHue
    val secondarySat = (primarySat * SECONDARY_SAT_MULTIPLIER).coerceIn(0f, 1f)
    val tertiaryHue = (primaryHue + TERTIARY_HUE_ROTATION + HUE_MAX) % HUE_MAX
    val tertiarySat = primarySat
    val neutralHue = primaryHue
    val neutralSat = primarySat * NEUTRAL_SAT_MULTIPLIER

    val tones = if (isDark) DarkTones else LightTones
    return if (isDark) {
        darkColorScheme(
            primary = tonedColor(primaryHue, primarySat, tones.primary),
            onPrimary = tonedColor(primaryHue, primarySat, tones.onPrimary),
            primaryContainer = tonedColor(primaryHue, primarySat, tones.primaryContainer),
            onPrimaryContainer = tonedColor(primaryHue, primarySat, tones.onPrimaryContainer),
            inversePrimary = tonedColor(primaryHue, primarySat, LightTones.primary),
            secondary = tonedColor(secondaryHue, secondarySat, tones.primary),
            onSecondary = tonedColor(secondaryHue, secondarySat, tones.onPrimary),
            secondaryContainer = tonedColor(secondaryHue, secondarySat, tones.primaryContainer),
            onSecondaryContainer = tonedColor(secondaryHue, secondarySat, tones.onPrimaryContainer),
            tertiary = tonedColor(tertiaryHue, tertiarySat, tones.primary),
            onTertiary = tonedColor(tertiaryHue, tertiarySat, tones.onPrimary),
            tertiaryContainer = tonedColor(tertiaryHue, tertiarySat, tones.primaryContainer),
            onTertiaryContainer = tonedColor(tertiaryHue, tertiarySat, tones.onPrimaryContainer),
            background = tonedColor(neutralHue, neutralSat, tones.background),
            onBackground = tonedColor(neutralHue, neutralSat, tones.onBackground),
            surface = tonedColor(neutralHue, neutralSat, tones.surface),
            onSurface = tonedColor(neutralHue, neutralSat, tones.onSurface),
            surfaceVariant = tonedColor(neutralHue, neutralSat, tones.surfaceVariant),
            onSurfaceVariant = tonedColor(neutralHue, neutralSat, tones.onSurfaceVariant),
            surfaceTint = tonedColor(primaryHue, primarySat, tones.primary),
            inverseSurface = tonedColor(neutralHue, neutralSat, LightTones.surface),
            inverseOnSurface = tonedColor(neutralHue, neutralSat, LightTones.onSurface),
            outline = tonedColor(neutralHue, neutralSat, tones.outline),
            outlineVariant = tonedColor(neutralHue, neutralSat, tones.outlineVariant),
            scrim = Color.Black,
            surfaceBright = tonedColor(neutralHue, neutralSat, tones.surfaceBright),
            surfaceContainer = tonedColor(neutralHue, neutralSat, tones.surfaceContainer),
            surfaceContainerHigh = tonedColor(neutralHue, neutralSat, tones.surfaceContainerHigh),
            surfaceContainerHighest = tonedColor(neutralHue, neutralSat, tones.surfaceContainerHighest),
            surfaceContainerLow = tonedColor(neutralHue, neutralSat, tones.surfaceContainerLow),
            surfaceContainerLowest = tonedColor(neutralHue, neutralSat, tones.surfaceContainerLowest),
            surfaceDim = tonedColor(neutralHue, neutralSat, tones.surfaceDim),
            error = tonedColor(ERROR_HUE, ERROR_SATURATION, tones.primary),
            onError = tonedColor(ERROR_HUE, ERROR_SATURATION, tones.onPrimary),
            errorContainer = tonedColor(ERROR_HUE, ERROR_SATURATION, tones.primaryContainer),
            onErrorContainer = tonedColor(ERROR_HUE, ERROR_SATURATION, tones.onPrimaryContainer),
        )
    } else {
        lightColorScheme(
            primary = tonedColor(primaryHue, primarySat, tones.primary),
            onPrimary = tonedColor(primaryHue, primarySat, tones.onPrimary),
            primaryContainer = tonedColor(primaryHue, primarySat, tones.primaryContainer),
            onPrimaryContainer = tonedColor(primaryHue, primarySat, tones.onPrimaryContainer),
            inversePrimary = tonedColor(primaryHue, primarySat, DarkTones.primary),
            secondary = tonedColor(secondaryHue, secondarySat, tones.primary),
            onSecondary = tonedColor(secondaryHue, secondarySat, tones.onPrimary),
            secondaryContainer = tonedColor(secondaryHue, secondarySat, tones.primaryContainer),
            onSecondaryContainer = tonedColor(secondaryHue, secondarySat, tones.onPrimaryContainer),
            tertiary = tonedColor(tertiaryHue, tertiarySat, tones.primary),
            onTertiary = tonedColor(tertiaryHue, tertiarySat, tones.onPrimary),
            tertiaryContainer = tonedColor(tertiaryHue, tertiarySat, tones.primaryContainer),
            onTertiaryContainer = tonedColor(tertiaryHue, tertiarySat, tones.onPrimaryContainer),
            background = tonedColor(neutralHue, neutralSat, tones.background),
            onBackground = tonedColor(neutralHue, neutralSat, tones.onBackground),
            surface = tonedColor(neutralHue, neutralSat, tones.surface),
            onSurface = tonedColor(neutralHue, neutralSat, tones.onSurface),
            surfaceVariant = tonedColor(neutralHue, neutralSat, tones.surfaceVariant),
            onSurfaceVariant = tonedColor(neutralHue, neutralSat, tones.onSurfaceVariant),
            surfaceTint = tonedColor(primaryHue, primarySat, tones.primary),
            inverseSurface = tonedColor(neutralHue, neutralSat, DarkTones.surface),
            inverseOnSurface = tonedColor(neutralHue, neutralSat, DarkTones.onSurface),
            outline = tonedColor(neutralHue, neutralSat, tones.outline),
            outlineVariant = tonedColor(neutralHue, neutralSat, tones.outlineVariant),
            scrim = Color.Black,
            surfaceBright = tonedColor(neutralHue, neutralSat, tones.surfaceBright),
            surfaceContainer = tonedColor(neutralHue, neutralSat, tones.surfaceContainer),
            surfaceContainerHigh = tonedColor(neutralHue, neutralSat, tones.surfaceContainerHigh),
            surfaceContainerHighest = tonedColor(neutralHue, neutralSat, tones.surfaceContainerHighest),
            surfaceContainerLow = tonedColor(neutralHue, neutralSat, tones.surfaceContainerLow),
            surfaceContainerLowest = tonedColor(neutralHue, neutralSat, tones.surfaceContainerLowest),
            surfaceDim = tonedColor(neutralHue, neutralSat, tones.surfaceDim),
            error = tonedColor(ERROR_HUE, ERROR_SATURATION, tones.primary),
            onError = tonedColor(ERROR_HUE, ERROR_SATURATION, tones.onPrimary),
            errorContainer = tonedColor(ERROR_HUE, ERROR_SATURATION, tones.primaryContainer),
            onErrorContainer = tonedColor(ERROR_HUE, ERROR_SATURATION, tones.onPrimaryContainer),
        )
    }
}

/**
 * Resolves a [ColorSource] to a concrete seed ARGB. [ColorSource.DynamicWallpaper] callers must
 * branch separately at the theme layer (it uses platform wallpaper extraction, not a seed).
 */
internal fun ColorSource.resolvePresetSeedArgb(): Int =
    when (this) {
        is ColorSource.Preset -> id.seedArgb
        is ColorSource.CustomSeed -> argb
        is ColorSource.DynamicWallpaper -> ColorPresetId.INDIGO.seedArgb
    }

private const val HUE_MAX = 360f
private const val TERTIARY_HUE_ROTATION = 60f
private const val SECONDARY_SAT_MULTIPLIER = 0.4f
private const val NEUTRAL_SAT_MULTIPLIER = 0.1f
private const val ERROR_HUE = 25f
private const val ERROR_SATURATION = 0.85f
private const val TONE_MAX = 100f
private const val RGB_MAX_INT = 255
private const val RGB_MAX_FLOAT = 255f
private const val ALPHA_OPAQUE = 0xFF
private const val ARGB_RED_SHIFT = 16
private const val ARGB_GREEN_SHIFT = 8
private const val ARGB_ALPHA_SHIFT = 24
private const val ARGB_BYTE_MASK = 0xFF

private data class PaletteTones(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val surfaceBright: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainerLowest: Int,
    val surfaceDim: Int,
    val outline: Int,
    val outlineVariant: Int,
)

private val LightTones = PaletteTones(
    primary = 40,
    onPrimary = 100,
    primaryContainer = 90,
    onPrimaryContainer = 10,
    background = 98,
    onBackground = 10,
    surface = 98,
    onSurface = 10,
    surfaceVariant = 90,
    onSurfaceVariant = 30,
    surfaceBright = 98,
    surfaceContainer = 94,
    surfaceContainerHigh = 92,
    surfaceContainerHighest = 90,
    surfaceContainerLow = 96,
    surfaceContainerLowest = 100,
    surfaceDim = 87,
    outline = 50,
    outlineVariant = 80,
)

private val DarkTones = PaletteTones(
    primary = 80,
    onPrimary = 20,
    primaryContainer = 30,
    onPrimaryContainer = 90,
    background = 10,
    onBackground = 90,
    surface = 10,
    onSurface = 90,
    surfaceVariant = 30,
    onSurfaceVariant = 80,
    surfaceBright = 24,
    surfaceContainer = 12,
    surfaceContainerHigh = 17,
    surfaceContainerHighest = 22,
    surfaceContainerLow = 10,
    surfaceContainerLowest = 4,
    surfaceDim = 6,
    outline = 60,
    outlineVariant = 30,
)

private fun extractHueAndSaturation(argb: Int): Pair<Float, Float> {
    val r = ((argb shr ARGB_RED_SHIFT) and ARGB_BYTE_MASK) / RGB_MAX_FLOAT
    val g = ((argb shr ARGB_GREEN_SHIFT) and ARGB_BYTE_MASK) / RGB_MAX_FLOAT
    val b = (argb and ARGB_BYTE_MASK) / RGB_MAX_FLOAT
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    val normalizedHue = if (hue < 0f) hue + HUE_MAX else hue
    val saturation = if (max == 0f) 0f else delta / max
    return normalizedHue to saturation
}

private fun tonedColor(hue: Float, saturation: Float, tone: Int): Color {
    val lightness = (tone.toFloat() / TONE_MAX).coerceIn(0f, 1f)
    val argb = hslToOpaqueArgb(hue, saturation, lightness)
    return Color(argb)
}

private fun hslToOpaqueArgb(hue: Float, saturation: Float, lightness: Float): Int {
    val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val hPrime = hue / 60f
    val x = c * (1f - kotlin.math.abs((hPrime % 2f) - 1f))
    val m = lightness - c / 2f
    val (r1, g1, b1) = when (hPrime.toInt()) {
        HSL_SECTOR_RED_YELLOW -> Triple(c, x, 0f)
        HSL_SECTOR_YELLOW_GREEN -> Triple(x, c, 0f)
        HSL_SECTOR_GREEN_CYAN -> Triple(0f, c, x)
        HSL_SECTOR_CYAN_BLUE -> Triple(0f, x, c)
        HSL_SECTOR_BLUE_MAGENTA -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val r = ((r1 + m) * RGB_MAX_FLOAT).toInt().coerceIn(0, RGB_MAX_INT)
    val g = ((g1 + m) * RGB_MAX_FLOAT).toInt().coerceIn(0, RGB_MAX_INT)
    val b = ((b1 + m) * RGB_MAX_FLOAT).toInt().coerceIn(0, RGB_MAX_INT)
    return (ALPHA_OPAQUE shl ARGB_ALPHA_SHIFT) or
        (r shl ARGB_RED_SHIFT) or
        (g shl ARGB_GREEN_SHIFT) or
        b
}

private const val HSL_SECTOR_RED_YELLOW = 0
private const val HSL_SECTOR_YELLOW_GREEN = 1
private const val HSL_SECTOR_GREEN_CYAN = 2
private const val HSL_SECTOR_CYAN_BLUE = 3
private const val HSL_SECTOR_BLUE_MAGENTA = 4
