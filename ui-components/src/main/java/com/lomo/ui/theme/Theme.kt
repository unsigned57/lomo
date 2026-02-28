package com.lomo.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import java.util.Locale

private val DarkColorScheme =
    darkColorScheme(
        primary = IndigoPrimaryDark,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF283275), // Dark Indigo
        onPrimaryContainer = Color(0xFFDEE0FF),
        secondary = SlateSecondaryDark,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF3B4656),
        onSecondaryContainer = Color(0xFFDCE2F0),
        tertiary = CyanTertiaryDark,
        onTertiary = Color.Black,
        background = Neutral10,
        onBackground = Neutral90,
        surface = SurfaceContainerLowDark, // Switch to Container Low for default surface in Dark
        onSurface = Neutral90,
        surfaceVariant = Neutral30,
        onSurfaceVariant = Neutral80,
        error = ErrorDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
        // M3 Surface Roles
        surfaceContainerLowest = SurfaceContainerLowestDark,
        surfaceContainerLow = SurfaceContainerLowDark,
        surfaceContainer = SurfaceContainerDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = SurfaceContainerHighestDark,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = IndigoPrimaryLight,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDEE0FF),
        onPrimaryContainer = Color(0xFF00105C),
        secondary = SlateSecondaryLight,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCE2F0),
        onSecondaryContainer = Color(0xFF131C2B),
        tertiary = CyanTertiaryLight,
        onTertiary = Color.White,
        background = Neutral99,
        onBackground = Neutral10,
        surface = Neutral99,
        onSurface = Neutral10,
        surfaceVariant = Neutral90,
        onSurfaceVariant = Neutral30,
        error = ErrorLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
        // M3 Surface Roles
        surfaceContainerLowest = SurfaceContainerLowestLight,
        surfaceContainerLow = SurfaceContainerLowLight,
        surfaceContainer = SurfaceContainerLight,
        surfaceContainerHigh = SurfaceContainerHighLight,
        surfaceContainerHighest = SurfaceContainerHighestLight,
    )

enum class ThemeMode(
    val storageValue: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStorageValue(value: String?): ThemeMode {
            val normalized = value?.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.storageValue == normalized } ?: SYSTEM
        }
    }
}

@Composable
fun LomoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme =
        when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }

    val targetColorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            }

            darkTheme -> {
                DarkColorScheme
            }

            else -> {
                LightColorScheme
            }
        }

    val animatedColorScheme = animateColorSchemeAsState(targetColorScheme)
    val systemFontWeightAdjustment =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.resources.configuration.fontWeightAdjustment
        } else {
            0
        }
    val typography = remember(systemFontWeightAdjustment) { Typography.withSystemFontWeightAdjustment(systemFontWeightAdjustment) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context.findActivity()
        SideEffect {
            activity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = animatedColorScheme, typography = typography, shapes = Shapes, content = content)
}

@Composable
fun LomoTheme(
    themeMode: String,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    LomoTheme(
        themeMode = ThemeMode.fromStorageValue(themeMode),
        dynamicColor = dynamicColor,
        content = content,
    )
}

@Composable
fun animateColorSchemeAsState(
    targetColorScheme: ColorScheme,
    animationSpec: AnimationSpec<Color> = tween(durationMillis = 220),
): ColorScheme {
    val primary by animateColorAsState(targetColorScheme.primary, animationSpec, label = "primary")
    val background by animateColorAsState(targetColorScheme.background, animationSpec, label = "background")
    val onBackground by animateColorAsState(targetColorScheme.onBackground, animationSpec, label = "onBackground")
    val surface by animateColorAsState(targetColorScheme.surface, animationSpec, label = "surface")
    val onSurface by animateColorAsState(targetColorScheme.onSurface, animationSpec, label = "onSurface")
    val surfaceVariant by animateColorAsState(targetColorScheme.surfaceVariant, animationSpec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(targetColorScheme.onSurfaceVariant, animationSpec, label = "onSurfaceVariant")
    val surfaceContainer by animateColorAsState(targetColorScheme.surfaceContainer, animationSpec, label = "surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(targetColorScheme.surfaceContainerHigh, animationSpec, label = "surfaceContainerHigh")
    val surfaceContainerHighest by animateColorAsState(
        targetColorScheme.surfaceContainerHighest,
        animationSpec,
        label = "surfaceContainerHighest",
    )
    val surfaceContainerLow by animateColorAsState(targetColorScheme.surfaceContainerLow, animationSpec, label = "surfaceContainerLow")
    val surfaceContainerLowest by animateColorAsState(
        targetColorScheme.surfaceContainerLowest,
        animationSpec,
        label = "surfaceContainerLowest",
    )

    return ColorScheme(
        primary = primary,
        onPrimary = targetColorScheme.onPrimary,
        primaryContainer = targetColorScheme.primaryContainer,
        onPrimaryContainer = targetColorScheme.onPrimaryContainer,
        inversePrimary = targetColorScheme.inversePrimary,
        secondary = targetColorScheme.secondary,
        onSecondary = targetColorScheme.onSecondary,
        secondaryContainer = targetColorScheme.secondaryContainer,
        onSecondaryContainer = targetColorScheme.onSecondaryContainer,
        tertiary = targetColorScheme.tertiary,
        onTertiary = targetColorScheme.onTertiary,
        tertiaryContainer = targetColorScheme.tertiaryContainer,
        onTertiaryContainer = targetColorScheme.onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = targetColorScheme.surfaceTint,
        inverseSurface = targetColorScheme.inverseSurface,
        inverseOnSurface = targetColorScheme.inverseOnSurface,
        error = targetColorScheme.error,
        onError = targetColorScheme.onError,
        errorContainer = targetColorScheme.errorContainer,
        onErrorContainer = targetColorScheme.onErrorContainer,
        outline = targetColorScheme.outline,
        outlineVariant = targetColorScheme.outlineVariant,
        scrim = targetColorScheme.scrim,
        surfaceBright = targetColorScheme.surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceDim = targetColorScheme.surfaceDim,
        primaryFixed = targetColorScheme.primaryFixed,
        primaryFixedDim = targetColorScheme.primaryFixedDim,
        onPrimaryFixed = targetColorScheme.onPrimaryFixed,
        onPrimaryFixedVariant = targetColorScheme.onPrimaryFixedVariant,
        secondaryFixed = targetColorScheme.secondaryFixed,
        secondaryFixedDim = targetColorScheme.secondaryFixedDim,
        onSecondaryFixed = targetColorScheme.onSecondaryFixed,
        onSecondaryFixedVariant = targetColorScheme.onSecondaryFixedVariant,
        tertiaryFixed = targetColorScheme.tertiaryFixed,
        tertiaryFixedDim = targetColorScheme.tertiaryFixedDim,
        onTertiaryFixed = targetColorScheme.onTertiaryFixed,
        onTertiaryFixedVariant = targetColorScheme.onTertiaryFixedVariant,
    )
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
