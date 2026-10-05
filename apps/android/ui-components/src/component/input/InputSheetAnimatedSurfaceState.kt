package com.lomo.ui.component.input

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateInt
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
    val transition = LocalInputSheetPresentationTransition.current
    val scheme = MaterialTheme.motionScheme
    val animatedCornerRadius by transition.animateDp(
        transitionSpec = { scheme.slowSpatialSpec() },
        label = "InputSheetCornerRadius",
    ) { state ->
        when (state.surfaceMotionStage()) {
            InputSheetMotionStage.Compact, InputSheetMotionStage.Collapsing -> InputSheetTokens.CompactCornerRadius
            InputSheetMotionStage.Expanding, InputSheetMotionStage.Expanded -> InputSheetTokens.ExpandedCornerRadius
        }
    }
    val animatedSurfaceHeightPx by transition.animateInt(
        transitionSpec = { scheme.slowSpatialSpec() },
        label = "InputSheetSurfaceHeight",
    ) { state ->
        when (state.surfaceMotionStage()) {
            InputSheetMotionStage.Compact, InputSheetMotionStage.Collapsing -> collapseTargetHeightPx
            InputSheetMotionStage.Expanding, InputSheetMotionStage.Expanded -> fullSurfaceHeightPx
        }
    }

    return InputSheetAnimatedSurfaceState(
        motionStage = motionStage,
        animatedCornerRadius = animatedCornerRadius.coerceAtLeast(androidx.compose.ui.unit.Dp(0f)),
        animatedSurfaceHeightPx = animatedSurfaceHeightPx.coerceIn(0, fullSurfaceHeightPx),
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
internal fun rememberInputSheetAnimatedInsets(): InputSheetAnimatedInsets {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val transition = LocalInputSheetPresentationTransition.current
    val scheme = MaterialTheme.motionScheme
    val animatedTopInset by transition.animateDp(
        transitionSpec = { scheme.slowSpatialSpec() },
        label = "InputSheetTopInset",
    ) { if (it.surfaceMotionStage().usesExpandedInsets()) statusBarHeight else InputSheetTokens.CollapsedInset }
    val animatedBottomInset by transition.animateDp(
        transitionSpec = { scheme.slowSpatialSpec() },
        label = "InputSheetBottomInset",
    ) { if (it.surfaceMotionStage().usesExpandedInsets()) navBarHeight else InputSheetTokens.CollapsedInset }
    return InputSheetAnimatedInsets(
        top = animatedTopInset.coerceAtLeast(InputSheetTokens.CollapsedInset),
        bottom = animatedBottomInset.coerceAtLeast(InputSheetTokens.CollapsedInset),
    )
}
