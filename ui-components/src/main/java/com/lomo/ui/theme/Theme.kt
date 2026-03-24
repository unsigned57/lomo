package com.lomo.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.lomo.ui.R
import java.util.Locale

private val DarkColorScheme =
    darkColorScheme(
        primary = IndigoPrimaryDark,
        onPrimary = Color.Black,
        primaryContainer = IndigoContainerDark,
        onPrimaryContainer = IndigoContainerLight,
        secondary = SlateSecondaryDark,
        onSecondary = Color.Black,
        secondaryContainer = SlateContainerDark,
        onSecondaryContainer = SlateContainerLight,
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
        primaryContainer = IndigoContainerLight,
        onPrimaryContainer = OnIndigoContainerLight,
        secondary = SlateSecondaryLight,
        onSecondary = Color.White,
        secondaryContainer = SlateContainerLight,
        onSecondaryContainer = OnSlateContainerLight,
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

private const val FONT_WEIGHT_ADJUSTMENT_FALLBACK = 0
private const val THEME_COLOR_ANIMATION_DURATION_MS = 220

private data class AnimatedCoreColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

private data class AnimatedSurfaceColors(
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceTint: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val surfaceBright: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerLowest: Color,
    val surfaceDim: Color,
)

private data class AnimatedFixedColors(
    val primaryFixed: Color,
    val primaryFixedDim: Color,
    val onPrimaryFixed: Color,
    val onPrimaryFixedVariant: Color,
    val secondaryFixed: Color,
    val secondaryFixedDim: Color,
    val onSecondaryFixed: Color,
    val onSecondaryFixedVariant: Color,
    val tertiaryFixed: Color,
    val tertiaryFixedDim: Color,
    val onTertiaryFixed: Color,
    val onTertiaryFixedVariant: Color,
)

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
            FONT_WEIGHT_ADJUSTMENT_FALLBACK
        }
    val typography =
        remember(systemFontWeightAdjustment) {
            Typography.withSystemFontWeightAdjustment(systemFontWeightAdjustment)
        }

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

    MaterialTheme(
        colorScheme = animatedColorScheme,
        typography = typography,
        shapes = Shapes,
        content = content,
    )
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
    animationSpec: AnimationSpec<Color> = tween(durationMillis = THEME_COLOR_ANIMATION_DURATION_MS),
): ColorScheme {
    val coreColors = animatedCoreColors(targetColorScheme, animationSpec)
    val surfaceColors = animatedSurfaceColors(targetColorScheme, animationSpec)
    val fixedColors = animatedFixedColors(targetColorScheme, animationSpec)

    return ColorScheme(
        primary = coreColors.primary,
        onPrimary = coreColors.onPrimary,
        primaryContainer = coreColors.primaryContainer,
        onPrimaryContainer = coreColors.onPrimaryContainer,
        inversePrimary = coreColors.inversePrimary,
        secondary = coreColors.secondary,
        onSecondary = coreColors.onSecondary,
        secondaryContainer = coreColors.secondaryContainer,
        onSecondaryContainer = coreColors.onSecondaryContainer,
        tertiary = coreColors.tertiary,
        onTertiary = coreColors.onTertiary,
        tertiaryContainer = coreColors.tertiaryContainer,
        onTertiaryContainer = coreColors.onTertiaryContainer,
        background = coreColors.background,
        onBackground = coreColors.onBackground,
        surface = surfaceColors.surface,
        onSurface = surfaceColors.onSurface,
        surfaceVariant = surfaceColors.surfaceVariant,
        onSurfaceVariant = surfaceColors.onSurfaceVariant,
        surfaceTint = surfaceColors.surfaceTint,
        inverseSurface = surfaceColors.inverseSurface,
        inverseOnSurface = surfaceColors.inverseOnSurface,
        error = coreColors.error,
        onError = coreColors.onError,
        errorContainer = coreColors.errorContainer,
        onErrorContainer = coreColors.onErrorContainer,
        outline = surfaceColors.outline,
        outlineVariant = surfaceColors.outlineVariant,
        scrim = surfaceColors.scrim,
        surfaceBright = surfaceColors.surfaceBright,
        surfaceContainer = surfaceColors.surfaceContainer,
        surfaceContainerHigh = surfaceColors.surfaceContainerHigh,
        surfaceContainerHighest = surfaceColors.surfaceContainerHighest,
        surfaceContainerLow = surfaceColors.surfaceContainerLow,
        surfaceContainerLowest = surfaceColors.surfaceContainerLowest,
        surfaceDim = surfaceColors.surfaceDim,
        primaryFixed = fixedColors.primaryFixed,
        primaryFixedDim = fixedColors.primaryFixedDim,
        onPrimaryFixed = fixedColors.onPrimaryFixed,
        onPrimaryFixedVariant = fixedColors.onPrimaryFixedVariant,
        secondaryFixed = fixedColors.secondaryFixed,
        secondaryFixedDim = fixedColors.secondaryFixedDim,
        onSecondaryFixed = fixedColors.onSecondaryFixed,
        onSecondaryFixedVariant = fixedColors.onSecondaryFixedVariant,
        tertiaryFixed = fixedColors.tertiaryFixed,
        tertiaryFixedDim = fixedColors.tertiaryFixedDim,
        onTertiaryFixed = fixedColors.onTertiaryFixed,
        onTertiaryFixedVariant = fixedColors.onTertiaryFixedVariant,
    )
}

@Composable
private fun animatedCoreColors(
    targetColorScheme: ColorScheme,
    animationSpec: AnimationSpec<Color>,
): AnimatedCoreColors =
    withAnimatedColor(animationSpec) { animateColor ->
        AnimatedCoreColors(
            primary = animateColor(targetColorScheme.primary, "primary"),
            onPrimary = animateColor(targetColorScheme.onPrimary, "onPrimary"),
            primaryContainer = animateColor(targetColorScheme.primaryContainer, "primaryContainer"),
            onPrimaryContainer = animateColor(targetColorScheme.onPrimaryContainer, "onPrimaryContainer"),
            inversePrimary = animateColor(targetColorScheme.inversePrimary, "inversePrimary"),
            secondary = animateColor(targetColorScheme.secondary, "secondary"),
            onSecondary = animateColor(targetColorScheme.onSecondary, "onSecondary"),
            secondaryContainer = animateColor(targetColorScheme.secondaryContainer, "secondaryContainer"),
            onSecondaryContainer = animateColor(targetColorScheme.onSecondaryContainer, "onSecondaryContainer"),
            tertiary = animateColor(targetColorScheme.tertiary, "tertiary"),
            onTertiary = animateColor(targetColorScheme.onTertiary, "onTertiary"),
            tertiaryContainer = animateColor(targetColorScheme.tertiaryContainer, "tertiaryContainer"),
            onTertiaryContainer = animateColor(targetColorScheme.onTertiaryContainer, "onTertiaryContainer"),
            background = animateColor(targetColorScheme.background, "background"),
            onBackground = animateColor(targetColorScheme.onBackground, "onBackground"),
            error = animateColor(targetColorScheme.error, "error"),
            onError = animateColor(targetColorScheme.onError, "onError"),
            errorContainer = animateColor(targetColorScheme.errorContainer, "errorContainer"),
            onErrorContainer = animateColor(targetColorScheme.onErrorContainer, "onErrorContainer"),
        )
    }

@Composable
private fun animatedSurfaceColors(
    targetColorScheme: ColorScheme,
    animationSpec: AnimationSpec<Color>,
): AnimatedSurfaceColors =
    withAnimatedColor(animationSpec) { animateColor ->
        AnimatedSurfaceColors(
            surface = animateColor(targetColorScheme.surface, "surface"),
            onSurface = animateColor(targetColorScheme.onSurface, "onSurface"),
            surfaceVariant = animateColor(targetColorScheme.surfaceVariant, "surfaceVariant"),
            onSurfaceVariant = animateColor(targetColorScheme.onSurfaceVariant, "onSurfaceVariant"),
            surfaceTint = animateColor(targetColorScheme.surfaceTint, "surfaceTint"),
            inverseSurface = animateColor(targetColorScheme.inverseSurface, "inverseSurface"),
            inverseOnSurface = animateColor(targetColorScheme.inverseOnSurface, "inverseOnSurface"),
            outline = animateColor(targetColorScheme.outline, "outline"),
            outlineVariant = animateColor(targetColorScheme.outlineVariant, "outlineVariant"),
            scrim = animateColor(targetColorScheme.scrim, "scrim"),
            surfaceBright = animateColor(targetColorScheme.surfaceBright, "surfaceBright"),
            surfaceContainer = animateColor(targetColorScheme.surfaceContainer, "surfaceContainer"),
            surfaceContainerHigh = animateColor(targetColorScheme.surfaceContainerHigh, "surfaceContainerHigh"),
            surfaceContainerHighest =
                animateColor(
                    targetColorScheme.surfaceContainerHighest,
                    "surfaceContainerHighest",
                ),
            surfaceContainerLow = animateColor(targetColorScheme.surfaceContainerLow, "surfaceContainerLow"),
            surfaceContainerLowest = animateColor(targetColorScheme.surfaceContainerLowest, "surfaceContainerLowest"),
            surfaceDim = animateColor(targetColorScheme.surfaceDim, "surfaceDim"),
        )
    }

@Composable
private fun animatedFixedColors(
    targetColorScheme: ColorScheme,
    animationSpec: AnimationSpec<Color>,
): AnimatedFixedColors =
    withAnimatedColor(animationSpec) { animateColor ->
        AnimatedFixedColors(
            primaryFixed = animateColor(targetColorScheme.primaryFixed, "primaryFixed"),
            primaryFixedDim = animateColor(targetColorScheme.primaryFixedDim, "primaryFixedDim"),
            onPrimaryFixed = animateColor(targetColorScheme.onPrimaryFixed, "onPrimaryFixed"),
            onPrimaryFixedVariant = animateColor(targetColorScheme.onPrimaryFixedVariant, "onPrimaryFixedVariant"),
            secondaryFixed = animateColor(targetColorScheme.secondaryFixed, "secondaryFixed"),
            secondaryFixedDim = animateColor(targetColorScheme.secondaryFixedDim, "secondaryFixedDim"),
            onSecondaryFixed = animateColor(targetColorScheme.onSecondaryFixed, "onSecondaryFixed"),
            onSecondaryFixedVariant =
                animateColor(
                    targetColorScheme.onSecondaryFixedVariant,
                    "onSecondaryFixedVariant",
                ),
            tertiaryFixed = animateColor(targetColorScheme.tertiaryFixed, "tertiaryFixed"),
            tertiaryFixedDim = animateColor(targetColorScheme.tertiaryFixedDim, "tertiaryFixedDim"),
            onTertiaryFixed = animateColor(targetColorScheme.onTertiaryFixed, "onTertiaryFixed"),
            onTertiaryFixedVariant = animateColor(targetColorScheme.onTertiaryFixedVariant, "onTertiaryFixedVariant"),
        )
    }

@Composable
private inline fun <T> withAnimatedColor(
    animationSpec: AnimationSpec<Color>,
    block: (@Composable (Color, String) -> Color) -> T,
): T {
    val animateColor: @Composable (Color, String) -> Color = { targetColor, label ->
        animatedThemeColor(targetColor, animationSpec, label).value
    }
    return block(animateColor)
}

@Composable
private fun animatedThemeColor(
    targetColor: Color,
    animationSpec: AnimationSpec<Color>,
    label: String,
) = animateColorAsState(targetColor, animationSpec, label = label)

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
