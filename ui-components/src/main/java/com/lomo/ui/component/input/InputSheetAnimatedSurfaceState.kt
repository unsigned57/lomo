package com.lomo.ui.component.input

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.MotionTokens

internal data class InputSheetAnimatedSurfaceState(
    val motionStage: InputSheetMotionStage,
    val animatedCornerRadius: Dp,
    val animatedSurfaceHeightPx: Int,
    val onCompactSurfaceHeightChanged: (Int) -> Unit,
)

internal data class InputSheetAnimatedInsets(
    val top: Dp,
    val bottom: Dp,
)

@Composable
internal fun rememberInputSheetAnimatedSurfaceState(
    presentationState: InputSheetPresentationState,
    fullSurfaceHeightPx: Int,
    fallbackCompactSurfaceHeightPx: Int,
): InputSheetAnimatedSurfaceState {
    val motionStage = presentationState.surfaceMotionStage()
    var compactSurfaceHeightPx by remember { mutableIntStateOf(0) }
    val collapseTargetHeightPx =
        compactSurfaceHeightPx.takeIf { it > 0 } ?: fallbackCompactSurfaceHeightPx
    val animatedCornerRadius by animateDpAsState(
        targetValue =
            if (motionStage == InputSheetMotionStage.Compact) {
                28.dp
            } else {
                0.dp
            },
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "InputSheetCornerRadius",
    )
    val animatedSurfaceHeightPx by animateIntAsState(
        targetValue =
            when (motionStage) {
                InputSheetMotionStage.Compact -> collapseTargetHeightPx
                InputSheetMotionStage.Expanding,
                InputSheetMotionStage.Expanded,
                -> fullSurfaceHeightPx
                InputSheetMotionStage.Collapsing -> collapseTargetHeightPx
            },
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing =
                    when (motionStage) {
                        InputSheetMotionStage.Collapsing ->
                            MotionTokens.EasingEmphasizedAccelerate
                        else -> MotionTokens.EasingEmphasizedDecelerate
                    },
            ),
        label = "InputSheetSurfaceHeight",
    )

    return InputSheetAnimatedSurfaceState(
        motionStage = motionStage,
        animatedCornerRadius = animatedCornerRadius,
        animatedSurfaceHeightPx = animatedSurfaceHeightPx,
        onCompactSurfaceHeightChanged = { compactSurfaceHeightPx = it },
    )
}

internal fun Modifier.inputSheetSurfaceHeight(
    motionStage: InputSheetMotionStage,
    animatedSurfaceHeightPx: Int,
    density: Density,
    onCompactSurfaceHeightChanged: (Int) -> Unit,
): Modifier =
    when (motionStage) {
        InputSheetMotionStage.Compact ->
            onSizeChanged { onCompactSurfaceHeightChanged(it.height) }
        InputSheetMotionStage.Expanding,
        InputSheetMotionStage.Collapsing,
        -> height(with(density) { animatedSurfaceHeightPx.toDp() })
        InputSheetMotionStage.Expanded -> fillMaxHeight()
    }

@Composable
internal fun rememberInputSheetAnimatedInsets(motionStage: InputSheetMotionStage): InputSheetAnimatedInsets {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val animatedTopInset by animateDpAsState(
        targetValue = if (motionStage.usesExpandedInsets()) statusBarHeight else 0.dp,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "InputSheetTopInset",
    )
    val animatedBottomInset by animateDpAsState(
        targetValue = if (motionStage.usesExpandedInsets()) navBarHeight else 0.dp,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationMedium2,
                easing = MotionTokens.EasingEmphasizedDecelerate,
            ),
        label = "InputSheetBottomInset",
    )
    return InputSheetAnimatedInsets(
        top = animatedTopInset,
        bottom = animatedBottomInset,
    )
}
