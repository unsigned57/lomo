package com.lomo.app.feature.main

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.ui.theme.MotionTokens

private const val MEMO_ITEM_HIDDEN_ALPHA = 0f
private const val MEMO_ITEM_VISIBLE_ALPHA = 1f
private const val MEMO_DELETE_ANIMATION_DURATION_MILLIS = 300
private const val MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS = 220

@Composable
internal fun rememberDeleteAlpha(isDeleting: Boolean): Float =
    if (isDeleting) {
        val animatedDeleteAlpha by animateFloatAsState(
            targetValue =
                if (isDeleting) {
                    MEMO_ITEM_HIDDEN_ALPHA
                } else {
                    MEMO_ITEM_VISIBLE_ALPHA
                },
            animationSpec =
                androidx.compose.animation.core.tween(
                    durationMillis = MEMO_DELETE_ANIMATION_DURATION_MILLIS,
                    easing = com.lomo.ui.theme.MotionTokens.EasingStandard,
                ),
            label = "DeleteAlpha",
        )
        animatedDeleteAlpha
    } else {
        MEMO_ITEM_VISIBLE_ALPHA
    }

@Composable
internal fun rememberAnimatedBottomSpacing(
    isCollapsing: Boolean,
    bottomSpacing: Dp,
): Dp =
    if (isCollapsing) {
        val collapseSpacing by animateDpAsState(
            targetValue = if (isCollapsing) 0.dp else bottomSpacing,
            animationSpec =
                androidx.compose.animation.core.tween(
                    durationMillis = MEMO_COLLAPSE_ANIMATION_DURATION_MILLIS,
                    easing = MotionTokens.EasingStandard,
                ),
            label = "DeleteSpacing",
        )
        collapseSpacing
    } else {
        bottomSpacing
    }
