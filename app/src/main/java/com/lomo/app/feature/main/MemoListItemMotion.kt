package com.lomo.app.feature.main

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.MotionTokens

private const val MEMO_DELETE_FADE_DURATION_MILLIS = 300
private const val MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS = 300

@Composable
internal fun rememberAnimatedBottomSpacing(
    isCollapsing: Boolean,
    bottomSpacing: Dp,
): Dp {
    val collapseSpacing by animateDpAsState(
        targetValue = if (isCollapsing) 0.dp else bottomSpacing,
        animationSpec =
            tween(
                durationMillis = MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS,
                easing = MotionTokens.EasingStandard,
            ),
        label = "DeleteSpacing",
    )
    return collapseSpacing
}
