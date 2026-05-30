package com.lomo.app.feature.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.MotionTokens

private const val SEARCH_INPUT_FOCUSED_CORNER_DP = 20
private const val SEARCH_INPUT_RESTING_CORNER_DP = 32

internal data class SearchInputMorphTargets(
    val containerColor: Color,
    val leadingIconTint: Color,
    val cornerRadius: Dp,
    val tonalElevation: Dp,
) {
    companion object {
        fun fromFocus(
            isFocused: Boolean,
            colorScheme: ColorScheme,
        ): SearchInputMorphTargets =
            if (isFocused) {
                SearchInputMorphTargets(
                    containerColor = colorScheme.surfaceContainerHighest,
                    leadingIconTint = colorScheme.primary,
                    cornerRadius = SEARCH_INPUT_FOCUSED_CORNER_DP.dp,
                    tonalElevation = 6.dp,
                )
            } else {
                SearchInputMorphTargets(
                    containerColor = colorScheme.surfaceContainerHigh,
                    leadingIconTint = colorScheme.onSurfaceVariant,
                    cornerRadius = SEARCH_INPUT_RESTING_CORNER_DP.dp,
                    tonalElevation = 3.dp,
                )
            }
    }
}

internal data class SearchInputMorph(
    val containerColor: Color,
    val leadingIconTint: Color,
    val shape: Shape,
    val tonalElevation: Dp,
)

@Composable
internal fun rememberSearchInputMorph(isFocused: Boolean): SearchInputMorph {
    val colorScheme = MaterialTheme.colorScheme
    val targets =
        remember(isFocused, colorScheme) {
            SearchInputMorphTargets.fromFocus(isFocused = isFocused, colorScheme = colorScheme)
        }
    val containerColor by animateColorAsState(
        targetValue = targets.containerColor,
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "SearchInputContainerColor",
    )
    val leadingIconTint by animateColorAsState(
        targetValue = targets.leadingIconTint,
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "SearchInputLeadingIconTint",
    )
    val cornerRadius by animateDpAsState(
        targetValue = targets.cornerRadius,
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "SearchInputCornerRadius",
    )
    val tonalElevation by animateDpAsState(
        targetValue = targets.tonalElevation,
        animationSpec =
            tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "SearchInputTonalElevation",
    )
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    return SearchInputMorph(
        containerColor = containerColor,
        leadingIconTint = leadingIconTint,
        shape = shape,
        tonalElevation = tonalElevation,
    )
}
