package com.lomo.ui.component.input

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

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
internal fun InputSheetDismissScrim(scrimAlpha: Float, enabled: Boolean = true, onRequestDismiss: () -> Unit) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(onTap = { onRequestDismiss() })
                }
            },
    )
}

internal fun inputSheetVisibilityEnterTransition(motionScheme: MotionScheme): EnterTransition = fadeIn(
    animationSpec = motionScheme.defaultEffectsSpec(),
) +
    slideInVertically(
        initialOffsetY = { height -> height / 3 },
        animationSpec = motionScheme.defaultSpatialSpec(),
    )

internal fun inputSheetVisibilityExitTransition(motionScheme: MotionScheme): ExitTransition = fadeOut(
    animationSpec = motionScheme.fastEffectsSpec(),
) +
    slideOutVertically(
        targetOffsetY = { height -> height / 3 },
        animationSpec = motionScheme.fastSpatialSpec(),
    )
