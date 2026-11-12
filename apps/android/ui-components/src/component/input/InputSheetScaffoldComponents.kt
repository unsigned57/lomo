package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.offset
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.theme.LocalLomoMotion

private const val INPUT_SHEET_BACK_SCALE_REDUCTION = 0.04f

/**
 * Window-level host of the input sheet: scrim, focus parking target and the morphing surface.
 * The sheet overlays its whole window, so geometry is resolved against the window width.
 */
@Composable
internal fun InputSheetScaffold(
    isSheetVisible: Boolean,
    expansion: InputSheetExpansionState,
    onRequestDismiss: () -> Unit,
    benchmarkRootTag: String?,
    focusParkingRequester: FocusRequester,
    content: @Composable () -> Unit,
) {
    val motionScheme = LocalLomoMotion.current.layout
    val scrimAlpha by animateFloatAsState(
        targetValue = if (isSheetVisible) InputSheetTokens.ScrimAlpha else 0f,
        animationSpec = motionScheme.defaultEffectsSpec(),
        label = "InputSheetScrimAlpha",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        InputSheetFocusParkingTarget(focusParkingRequester = focusParkingRequester)
        InputSheetDismissScrim(
            scrimAlpha = scrimAlpha,
            enabled = isSheetVisible,
            onRequestDismiss = onRequestDismiss,
        )
        AnimatedVisibility(
            visibleState = LocalInputSheetVisibilityState.current,
            modifier = Modifier.fillMaxSize(),
            enter = inputSheetVisibilityEnterTransition(motionScheme),
            exit = inputSheetVisibilityExitTransition(motionScheme),
        ) {
            InputSheetSurface(
                expansion = expansion,
                benchmarkRootTag = benchmarkRootTag,
                content = content,
            )
        }
    }
}

@Composable
private fun InputSheetSurface(
    expansion: InputSheetExpansionState,
    benchmarkRootTag: String?,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val backProgress = LocalInputSheetBackProgress.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    val geometry =
        remember(windowWidthPx, statusBarTopPx, density) {
            with(density) {
                resolveInputSheetGeometry(
                    containerWidth = windowWidthPx.toDp(),
                    statusBarTop = statusBarTopPx.toDp(),
                )
            }
        }
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier =
            Modifier
                .inputSheetFrame(expansion, geometry)
                .benchmarkAnchorRoot(benchmarkRootTag)
                .inputSheetSurfaceLayer(expansion, geometry, backProgress)
                .background(surfaceColor)
                .pointerInput(Unit) { detectTapGestures(onTap = { }) }
                .inputSheetContentTopInset(expansion, geometry)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            content()
        }
    }
}

/** Width and the clear space above the surface follow the morph; the content decides the height. */
private fun Modifier.inputSheetFrame(expansion: InputSheetExpansionState, geometry: InputSheetGeometry): Modifier =
    layout { measurable, constraints ->
        val form = geometry.at(expansion.fraction)
        val width = form.width.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
        val maxHeight = (constraints.maxHeight - form.topGap.roundToPx()).coerceAtLeast(constraints.minHeight)
        val placeable =
            measurable.measure(
                Constraints(
                    minWidth = width,
                    maxWidth = width,
                    minHeight = constraints.minHeight,
                    maxHeight = maxHeight,
                ),
            )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** Corner radius and the predictive-back preview are draw-phase only: motion never relayouts them. */
private fun Modifier.inputSheetSurfaceLayer(
    expansion: InputSheetExpansionState,
    geometry: InputSheetGeometry,
    backProgress: State<Float>,
): Modifier = graphicsLayer {
    val back = backProgress.value
    val scale = 1f - INPUT_SHEET_BACK_SCALE_REDUCTION * back
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin.Center
    translationY = InputSheetTokens.BackPreviewTravel.toPx() * back
    val radius = geometry.at(expansion.fraction).cornerRadius
    shape = RoundedCornerShape(topStart = radius, topEnd = radius)
    clip = true
}

/** A full-bleed surface slides under the status bar while its content stays on the status-bar line. */
private fun Modifier.inputSheetContentTopInset(
    expansion: InputSheetExpansionState,
    geometry: InputSheetGeometry,
): Modifier = layout { measurable, constraints ->
    val inset =
        geometry.at(expansion.fraction).contentTopInset.roundToPx()
            .coerceAtMost(constraints.maxHeight)
    val placeable = measurable.measure(constraints.offset(vertical = -inset))
    layout(placeable.width, placeable.height + inset) { placeable.place(0, inset) }
}

internal val LocalInputSheetVisibilityState =
    androidx.compose.runtime.staticCompositionLocalOf<MutableTransitionState<Boolean>> {
        error("Input sheet visibility must be hosted by InputSheet")
    }

internal val LocalInputSheetBackProgress =
    androidx.compose.runtime.staticCompositionLocalOf<State<Float>> {
        error("Input sheet back preview must be hosted by InputSheet")
    }
