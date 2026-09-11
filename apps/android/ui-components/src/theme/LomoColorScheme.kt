package com.lomo.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import com.lomo.domain.model.ColorSource

/**
 * Canonical [ColorSource] -> [ColorScheme] resolution. This is the single mapping the whole app uses
 * so every surface (in-app composables via [LomoTheme] and off-composition renderers such as the
 * share-card bitmap) derives colors from the user's selected palette identically.
 *
 * - [ColorSource.DynamicWallpaper] uses platform wallpaper extraction on API 31+, falling back to the
 *   preset seed below.
 * - [ColorSource.Preset] / [ColorSource.CustomSeed] flow through [colorSchemeFromSeed].
 */
fun resolveLomoColorScheme(
    context: Context,
    colorSource: ColorSource,
    isDark: Boolean,
): ColorScheme =
    when (colorSource) {
        is ColorSource.DynamicWallpaper ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                colorSchemeFromSeed(colorSource.resolvePresetSeedArgb(), isDark)
            }
        is ColorSource.Preset -> colorSchemeFromSeed(colorSource.id.seedArgb, isDark)
        is ColorSource.CustomSeed -> colorSchemeFromSeed(colorSource.argb, isDark)
    }

/**
 * [resolveLomoColorScheme] for callers that only know the configured [com.lomo.domain.model.ThemeMode]
 * and cannot observe Compose's `isSystemInDarkTheme()` (e.g. the off-composition share-card renderer).
 * The domain theme mode is bridged to the UI [ThemeMode] by its shared storage value, and dark
 * resolution comes from the current configuration's UI mode.
 */
fun resolveLomoColorScheme(
    context: Context,
    colorSource: ColorSource,
    themeMode: com.lomo.domain.model.ThemeMode,
): ColorScheme =
    resolveLomoColorScheme(
        context = context,
        colorSource = colorSource,
        isDark =
            resolveDarkTheme(
                themeMode = ThemeMode.fromStorageValue(themeMode.value),
                currentUiMode = context.resources.configuration.uiMode,
                systemDarkTheme = false,
            ),
    )
