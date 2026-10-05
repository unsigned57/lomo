package com.lomo.ui.component.input

import androidx.compose.runtime.State

import androidx.compose.animation.core.MutableTransitionState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.theme.SheetHandleTokens

private const val INPUT_SHEET_BACK_SCALE_REDUCTION = 0.04f

@Composable
internal fun InputSheetScaffold(
    isSheetVisible: Boolean,
    presentationState: InputSheetPresentationState,
    scrimAlpha: Float,
    onRequestDismiss: () -> Unit,
    benchmarkRootTag: String?,
    focusParkingRequester: FocusRequester,
    content: @Composable (InputSheetMotionStage, Modifier) -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    val animatedScrimAlpha by animateFloatAsState(
        targetValue = scrimAlpha,
        animationSpec =
            motionScheme.defaultEffectsSpec(),
        label = "InputSheetScrimAlpha",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        InputSheetFocusParkingTarget(focusParkingRequester = focusParkingRequester)
        InputSheetDismissScrim(
            scrimAlpha = animatedScrimAlpha,
            enabled = isSheetVisible,
            onRequestDismiss = onRequestDismiss,
        )
        AnimatedVisibility(
            visibleState = LocalInputSheetVisibilityState.current,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize(),
            enter = inputSheetVisibilityEnterTransition(motionScheme),
            exit = inputSheetVisibilityExitTransition(motionScheme),
        ) {
            InputSheetAnimatedSurface(
                presentationState = presentationState,
                benchmarkRootTag = benchmarkRootTag,
            ) { motionStage, contentModifier ->
                InputSheetSurfaceContent(
                    motionStage = motionStage,
                    modifier = contentModifier,
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun InputSheetAnimatedSurface(
    presentationState: InputSheetPresentationState,
    benchmarkRootTag: String?,
    content: @Composable (InputSheetMotionStage, Modifier) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val backProgress = LocalInputSheetBackProgress.current
        val density = LocalDensity.current
        val fullSurfaceHeightPx = with(density) { maxHeight.roundToPx() }
        val surfaceState =
            rememberInputSheetAnimatedSurfaceState(
                presentationState = presentationState,
                fullSurfaceHeightPx = fullSurfaceHeightPx,
                fallbackCompactSurfaceHeightPx =
                    remember(density) {
                        with(density) { InputSheetTokens.CompactFallbackHeight.roundToPx() }
                    },
            )
        val animatedInsets = rememberInputSheetAnimatedInsets()

        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier =
                    Modifier
                        .benchmarkAnchorRoot(benchmarkRootTag)
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .inputSheetSurfaceHeight(
                            motionStage = surfaceState.motionStage,
                            animatedSurfaceHeightPx = surfaceState.animatedSurfaceHeightPx,
                            density = density,
                            onCompactSurfaceHeightChanged = surfaceState.onCompactSurfaceHeightChanged,
                        )
                        .graphicsLayer {
                            val fraction = backProgress.value
                            scaleX = 1f - INPUT_SHEET_BACK_SCALE_REDUCTION * fraction
                            scaleY = 1f - INPUT_SHEET_BACK_SCALE_REDUCTION * fraction
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                            translationY = 24.dp.toPx() * fraction
                        }
                        .clip(
                            RoundedCornerShape(
                                topStart = surfaceState.animatedCornerRadius,
                                topEnd = surfaceState.animatedCornerRadius,
                            ),
                        )
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) { detectTapGestures(onTap = { }) },
            ) {
                content(
                    surfaceState.motionStage,
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (surfaceState.motionStage.usesExpandedSurfaceForm()) {
                                Modifier.fillMaxHeight()
                            } else {
                                Modifier
                            },
                        )
                        .padding(
                            top = animatedInsets.top,
                            bottom = animatedInsets.bottom,
                        )
                        .windowInsetsPadding(WindowInsets.ime),
                )
            }
        }
    }
}

@Composable
internal fun InputSheetDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .padding(vertical = SheetHandleTokens.VerticalPadding)
                .width(SheetHandleTokens.Width)
                .height(SheetHandleTokens.Height)
                .clip(SheetHandleTokens.Shape)
                .background(SheetHandleTokens.color(MaterialTheme.colorScheme))
                .clearAndSetSemantics { },
    )
}

internal val LocalInputSheetVisibilityState =
    androidx.compose.runtime.staticCompositionLocalOf<MutableTransitionState<Boolean>> {
        error("Input sheet visibility must be hosted by InputSheet")
    }

internal val LocalInputSheetBackProgress =
    androidx.compose.runtime.staticCompositionLocalOf<State<Float>> {
        error("Input sheet back preview must be hosted by InputSheet")
    }
