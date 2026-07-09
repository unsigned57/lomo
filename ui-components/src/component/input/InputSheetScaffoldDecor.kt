package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.lomo.ui.theme.MotionTokens

@Composable
internal fun InputSheetFocusParkingTarget(focusParkingRequester: FocusRequester) {
    Box(
        modifier =
            Modifier
                .size(1.dp)
                .focusRequester(focusParkingRequester)
                .focusable()
                .clearAndSetSemantics { },
    )
}

@Composable
internal fun InputSheetDismissScrim(
    scrimAlpha: Float,
    onRequestDismiss: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                .pointerInput(Unit) { detectTapGestures(onTap = { onRequestDismiss() }) },
    )
}

internal fun inputSheetVisibilityEnterTransition(): EnterTransition =
    slideInVertically(
        initialOffsetY = { height -> height },
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationLong2,
                easing = MotionTokens.EasingStandard,
            ),
    )

internal fun inputSheetVisibilityExitTransition(): ExitTransition =
    slideOutVertically(
        targetOffsetY = { height -> height },
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = MotionTokens.DurationLong2,
                easing = MotionTokens.EasingStandard,
            ),
    )

@Composable
internal fun InputSheetSurfaceContent(
    motionStage: InputSheetMotionStage,
    modifier: Modifier = Modifier,
    content: @Composable (InputSheetMotionStage, Modifier) -> Unit,
) {
    Column(modifier = modifier) {
        InputSheetCompactChrome(visible = motionStage.showsCompactChrome())
        content(
            motionStage,
            Modifier
                .fillMaxWidth()
                .then(
                    if (motionStage.usesExpandedSurfaceForm()) {
                        Modifier.weight(1f)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

@Composable
private fun InputSheetCompactChrome(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(
                animationSpec =
                    androidx.compose.animation.core.tween(
                        durationMillis = MotionTokens.DurationMedium2,
                    ),
            ) +
                expandVertically(
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = MotionTokens.DurationMedium2,
                            easing = MotionTokens.EasingEmphasizedDecelerate,
                        ),
                ),
        exit =
            fadeOut(
                animationSpec =
                    androidx.compose.animation.core.tween(
                        durationMillis = MotionTokens.DurationShort4,
                    ),
            ) +
                shrinkVertically(
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = MotionTokens.DurationShort4,
                            easing = MotionTokens.EasingEmphasizedAccelerate,
                        ),
                ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            InputSheetDragHandle(
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
