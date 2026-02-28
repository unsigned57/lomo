package com.lomo.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
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
    val configuration = LocalConfiguration.current
    val systemFontWeightAdjustment =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            configuration.fontWeightAdjustment
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
    val onPrimary by animateColorAsState(targetColorScheme.onPrimary, animationSpec, label = "onPrimary")
    val primaryContainer by animateColorAsState(targetColorScheme.primaryContainer, animationSpec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(targetColorScheme.onPrimaryContainer, animationSpec, label = "onPrimaryContainer")
    val inversePrimary by animateColorAsState(targetColorScheme.inversePrimary, animationSpec, label = "inversePrimary")
    val secondary by animateColorAsState(targetColorScheme.secondary, animationSpec, label = "secondary")
    val onSecondary by animateColorAsState(targetColorScheme.onSecondary, animationSpec, label = "onSecondary")
    val secondaryContainer by animateColorAsState(targetColorScheme.secondaryContainer, animationSpec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(
        targetColorScheme.onSecondaryContainer,
        animationSpec,
        label = "onSecondaryContainer",
    )
    val tertiary by animateColorAsState(targetColorScheme.tertiary, animationSpec, label = "tertiary")
    val onTertiary by animateColorAsState(targetColorScheme.onTertiary, animationSpec, label = "onTertiary")
    val tertiaryContainer by animateColorAsState(targetColorScheme.tertiaryContainer, animationSpec, label = "tertiaryContainer")
    val onTertiaryContainer by animateColorAsState(targetColorScheme.onTertiaryContainer, animationSpec, label = "onTertiaryContainer")
    val background by animateColorAsState(targetColorScheme.background, animationSpec, label = "background")
    val onBackground by animateColorAsState(targetColorScheme.onBackground, animationSpec, label = "onBackground")
    val surface by animateColorAsState(targetColorScheme.surface, animationSpec, label = "surface")
    val onSurface by animateColorAsState(targetColorScheme.onSurface, animationSpec, label = "onSurface")
    val surfaceVariant by animateColorAsState(targetColorScheme.surfaceVariant, animationSpec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(targetColorScheme.onSurfaceVariant, animationSpec, label = "onSurfaceVariant")
    val surfaceTint by animateColorAsState(targetColorScheme.surfaceTint, animationSpec, label = "surfaceTint")
    val inverseSurface by animateColorAsState(targetColorScheme.inverseSurface, animationSpec, label = "inverseSurface")
    val inverseOnSurface by animateColorAsState(targetColorScheme.inverseOnSurface, animationSpec, label = "inverseOnSurface")
    val error by animateColorAsState(targetColorScheme.error, animationSpec, label = "error")
    val onError by animateColorAsState(targetColorScheme.onError, animationSpec, label = "onError")
    val errorContainer by animateColorAsState(targetColorScheme.errorContainer, animationSpec, label = "errorContainer")
    val onErrorContainer by animateColorAsState(targetColorScheme.onErrorContainer, animationSpec, label = "onErrorContainer")
    val outline by animateColorAsState(targetColorScheme.outline, animationSpec, label = "outline")
    val outlineVariant by animateColorAsState(targetColorScheme.outlineVariant, animationSpec, label = "outlineVariant")
    val scrim by animateColorAsState(targetColorScheme.scrim, animationSpec, label = "scrim")
    val surfaceBright by animateColorAsState(targetColorScheme.surfaceBright, animationSpec, label = "surfaceBright")
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
    val surfaceDim by animateColorAsState(targetColorScheme.surfaceDim, animationSpec, label = "surfaceDim")
    val primaryFixed by animateColorAsState(targetColorScheme.primaryFixed, animationSpec, label = "primaryFixed")
    val primaryFixedDim by animateColorAsState(targetColorScheme.primaryFixedDim, animationSpec, label = "primaryFixedDim")
    val onPrimaryFixed by animateColorAsState(targetColorScheme.onPrimaryFixed, animationSpec, label = "onPrimaryFixed")
    val onPrimaryFixedVariant by animateColorAsState(
        targetColorScheme.onPrimaryFixedVariant,
        animationSpec,
        label = "onPrimaryFixedVariant",
    )
    val secondaryFixed by animateColorAsState(targetColorScheme.secondaryFixed, animationSpec, label = "secondaryFixed")
    val secondaryFixedDim by animateColorAsState(targetColorScheme.secondaryFixedDim, animationSpec, label = "secondaryFixedDim")
    val onSecondaryFixed by animateColorAsState(targetColorScheme.onSecondaryFixed, animationSpec, label = "onSecondaryFixed")
    val onSecondaryFixedVariant by animateColorAsState(
        targetColorScheme.onSecondaryFixedVariant,
        animationSpec,
        label = "onSecondaryFixedVariant",
    )
    val tertiaryFixed by animateColorAsState(targetColorScheme.tertiaryFixed, animationSpec, label = "tertiaryFixed")
    val tertiaryFixedDim by animateColorAsState(targetColorScheme.tertiaryFixedDim, animationSpec, label = "tertiaryFixedDim")
    val onTertiaryFixed by animateColorAsState(targetColorScheme.onTertiaryFixed, animationSpec, label = "onTertiaryFixed")
    val onTertiaryFixedVariant by animateColorAsState(
        targetColorScheme.onTertiaryFixedVariant,
        animationSpec,
        label = "onTertiaryFixedVariant",
    )

    return ColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceDim = surfaceDim,
        primaryFixed = primaryFixed,
        primaryFixedDim = primaryFixedDim,
        onPrimaryFixed = onPrimaryFixed,
        onPrimaryFixedVariant = onPrimaryFixedVariant,
        secondaryFixed = secondaryFixed,
        secondaryFixedDim = secondaryFixedDim,
        onSecondaryFixed = onSecondaryFixed,
        onSecondaryFixedVariant = onSecondaryFixedVariant,
        tertiaryFixed = tertiaryFixed,
        tertiaryFixedDim = tertiaryFixedDim,
        onTertiaryFixed = onTertiaryFixed,
        onTertiaryFixedVariant = onTertiaryFixedVariant,
    )
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Preview(name = "Theme Light", showBackground = true)
@Composable
private fun LomoThemeLightPreview() {
    LomoTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.sidebar_memo),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Preview(name = "Theme Dark", showBackground = true)
@Composable
private fun LomoThemeDarkPreview() {
    LomoTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.sidebar_memo),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
