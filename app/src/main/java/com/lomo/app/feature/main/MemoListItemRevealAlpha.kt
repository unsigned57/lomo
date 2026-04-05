package com.lomo.app.feature.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.lomo.ui.theme.MotionTokens

private const val MEMO_ITEM_HIDDEN_ALPHA = 0f
private const val MEMO_ITEM_VISIBLE_ALPHA = 1f
private const val MEMO_INSERT_SPACE_ANIMATION_DURATION_MILLIS = 220
private const val MEMO_NEW_ITEM_REVEAL_DURATION_MILLIS = 300

internal data class MemoItemInsertAnimation(
    val spaceFraction: Float,
    val contentAlpha: Float,
)

@Composable
internal fun rememberMemoItemInsertAnimation(
    memoId: String,
    shouldHoldNewMemoHidden: Boolean,
    shouldHoldGapReadyMemoHidden: Boolean,
    shouldAnimateNewMemoSpace: Boolean,
    shouldAnimateNewMemoReveal: Boolean,
    onNewMemoSpacePrepared: (String) -> Unit,
    onNewMemoRevealConsumed: (String) -> Unit,
): MemoItemInsertAnimation {
    val initialSpace = remember(memoId) {
        if (shouldHoldNewMemoHidden || shouldAnimateNewMemoSpace) {
            MEMO_ITEM_HIDDEN_ALPHA
        } else {
            MEMO_ITEM_VISIBLE_ALPHA
        }
    }
    val initialContentAlpha = remember(memoId) {
        if (
            shouldHoldNewMemoHidden ||
            shouldHoldGapReadyMemoHidden ||
            shouldAnimateNewMemoSpace ||
            shouldAnimateNewMemoReveal
        ) {
            MEMO_ITEM_HIDDEN_ALPHA
        } else {
            MEMO_ITEM_VISIBLE_ALPHA
        }
    }

    val spaceFraction = remember(memoId) { Animatable(initialSpace) }
    val contentAlpha = remember(memoId) { Animatable(initialContentAlpha) }

    LaunchedEffect(
        shouldHoldNewMemoHidden,
        shouldHoldGapReadyMemoHidden,
        shouldAnimateNewMemoSpace,
        shouldAnimateNewMemoReveal,
        memoId,
    ) {
        when {
            shouldAnimateNewMemoSpace -> {
                spaceFraction.snapTo(MEMO_ITEM_HIDDEN_ALPHA)
                contentAlpha.snapTo(MEMO_ITEM_HIDDEN_ALPHA)
                withFrameNanos { }
                spaceFraction.animateTo(
                    targetValue = MEMO_ITEM_VISIBLE_ALPHA,
                    animationSpec =
                        tween(
                            durationMillis = MEMO_INSERT_SPACE_ANIMATION_DURATION_MILLIS,
                            easing = MotionTokens.EasingStandard,
                        ),
                )
                onNewMemoSpacePrepared(memoId)
            }

            shouldAnimateNewMemoReveal -> {
                spaceFraction.snapTo(MEMO_ITEM_VISIBLE_ALPHA)
                contentAlpha.snapTo(MEMO_ITEM_HIDDEN_ALPHA)
                withFrameNanos { }
                contentAlpha.animateTo(
                    targetValue = MEMO_ITEM_VISIBLE_ALPHA,
                    animationSpec =
                        tween(
                            durationMillis = MEMO_NEW_ITEM_REVEAL_DURATION_MILLIS,
                            easing = MotionTokens.EasingStandard,
                        ),
                )
                onNewMemoRevealConsumed(memoId)
            }

            shouldHoldGapReadyMemoHidden -> {
                spaceFraction.snapTo(MEMO_ITEM_VISIBLE_ALPHA)
                contentAlpha.snapTo(MEMO_ITEM_HIDDEN_ALPHA)
            }

            shouldHoldNewMemoHidden -> {
                spaceFraction.snapTo(MEMO_ITEM_HIDDEN_ALPHA)
                contentAlpha.snapTo(MEMO_ITEM_HIDDEN_ALPHA)
            }

            else -> {
                spaceFraction.snapTo(MEMO_ITEM_VISIBLE_ALPHA)
                contentAlpha.snapTo(MEMO_ITEM_VISIBLE_ALPHA)
            }
        }
    }

    return MemoItemInsertAnimation(
        spaceFraction = spaceFraction.value,
        contentAlpha = contentAlpha.value,
    )
}
