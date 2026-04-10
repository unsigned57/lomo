package com.lomo.ui.component.input

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.MotionTokens

internal val InputSheetCompactFallbackHeight = 228.dp

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
    val animatedScrimAlpha by animateFloatAsState(
        targetValue = scrimAlpha,
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationLong2,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
        label = "InputSheetScrimAlpha",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        InputSheetFocusParkingTarget(focusParkingRequester = focusParkingRequester)
        InputSheetDismissScrim(
            scrimAlpha = animatedScrimAlpha,
            onRequestDismiss = onRequestDismiss,
        )
        AnimatedVisibility(
            visible = isSheetVisible,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize(),
            enter = inputSheetVisibilityEnterTransition(),
            exit = inputSheetVisibilityExitTransition(),
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
        val density = LocalDensity.current
        val fullSurfaceHeightPx = with(density) { maxHeight.roundToPx() }
        val surfaceState =
            rememberInputSheetAnimatedSurfaceState(
                presentationState = presentationState,
                fullSurfaceHeightPx = fullSurfaceHeightPx,
                fallbackCompactSurfaceHeightPx =
                    remember(density) {
                        with(density) { InputSheetCompactFallbackHeight.roundToPx() }
                    },
            )
        val animatedInsets = rememberInputSheetAnimatedInsets(motionStage = surfaceState.motionStage)

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
                .padding(vertical = 22.dp)
                .width(32.dp)
                .height(4.dp)
                .clip(AppShapes.ExtraSmall)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                .clearAndSetSemantics { },
    )
}
