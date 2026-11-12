package com.lomo.ui.component.common

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * Applies the row-level two-phase enter/exit motion directly to a lazy item root.
 *
 * Behavior contract:
 * - Given a row is entering, then HEIGHT_FRACTION expands 0->1 before ALPHA fades 0->1.
 * - Given a row is exiting, then ALPHA fades 1->0 before HEIGHT_FRACTION collapses 1->0.
 * - Given enter and exit are both requested for the same row, then EXIT owns the row and
 *   the interrupted enter is settled so its registry cannot retain a stale id.
 * - Given no phase is active, then the row is fully visible and expanded.
 *
 * This is a modifier, not a composable wrapper, so the lazy item content tree is composed
 * once and does not introduce nested reuse-group boundaries inside the LazyColumn slot.
 */
@Composable
fun Modifier.lomoListItemPhaseMotion(
    isEntering: Boolean,
    onEnterSettled: () -> Unit,
    exitPhase: LomoListExitPhase?,
    onExitSettled: () -> Unit,
    isHeld: Boolean = false,
): Modifier = lomoListItemPhaseMotionInternal(
    isEntering = isEntering,
    onEnterSettled = onEnterSettled,
    exitPhase = exitPhase,
    onExitSettled = onExitSettled,
    isHeld = isHeld,
)

/**
 * Applies the row-level two-phase exit motion directly to a lazy item root.
 */
@Composable
fun Modifier.lomoListItemExitPhaseMotion(exitPhase: LomoListExitPhase?, onExitSettled: () -> Unit): Modifier =
    lomoListItemPhaseMotionInternal(
        isEntering = false,
        onEnterSettled = null,
        exitPhase = exitPhase,
        onExitSettled = onExitSettled,
        isHeld = false,
    )

@Composable
private fun Modifier.lomoListItemPhaseMotionInternal(
    isEntering: Boolean,
    onEnterSettled: (() -> Unit)?,
    exitPhase: LomoListExitPhase?,
    onExitSettled: () -> Unit,
    isHeld: Boolean,
): Modifier {
    val motionSpecs = rememberLomoListItemMotionSpecs()
    val isExitAnimationRunning = exitPhase == LomoListExitPhase.Exiting
    val isExitHidden = exitPhase == LomoListExitPhase.Hidden
    val isHidden = isExitHidden || (isHeld && exitPhase == null)
    val activeDirection = TwoPhaseAnimationPlanner.activeDirection(
        isEntering = isEntering,
        isExiting = isExitAnimationRunning,
    )
    val initialValue =
        when {
            isHidden -> 0f
            isEntering && !isExitAnimationRunning -> 0f
            else -> 1f
        }
    val alpha = remember { Animatable(initialValue) }
    val heightFraction = remember { Animatable(initialValue) }
    val enterPolicy = remember { TwoPhaseSettlePolicy() }
    val exitPolicy = remember { TwoPhaseSettlePolicy() }
    val currentOnEnterSettled by rememberUpdatedState(onEnterSettled)
    val currentOnExitSettled by rememberUpdatedState(onExitSettled)

    LaunchedEffect(activeDirection, isHidden, motionSpecs) {
        when {
            isHidden && !isExitHidden -> {
                alpha.snapTo(0f)
                heightFraction.snapTo(0f)
            }

            isExitHidden -> {
                currentOnEnterSettled?.let { settleEnter ->
                    enterPolicy.markSettled(settleEnter)
                }
                exitPolicy.markRollback()
                heightFraction.snapTo(0f)
                alpha.snapTo(0f)
            }

            activeDirection == TwoPhaseDirection.ENTER -> {
                exitPolicy.markRollback()
                enterPolicy.markStarted()
                val startValue = TwoPhaseAnimationPlanner.startValue(TwoPhaseDirection.ENTER)
                alpha.snapTo(startValue)
                heightFraction.snapTo(startValue)
                runListItemPhaseAnimation(
                    direction = TwoPhaseDirection.ENTER,
                    alpha = alpha,
                    heightFraction = heightFraction,
                    motionSpecs = motionSpecs,
                )
                enterPolicy.markSettled {
                    currentOnEnterSettled?.invoke()
                }
            }

            activeDirection == TwoPhaseDirection.EXIT -> {
                currentOnEnterSettled?.let { settleEnter ->
                    enterPolicy.markSettled(settleEnter)
                }
                exitPolicy.markStarted()
                runListItemPhaseAnimation(
                    direction = TwoPhaseDirection.EXIT,
                    alpha = alpha,
                    heightFraction = heightFraction,
                    motionSpecs = motionSpecs,
                )
                exitPolicy.markSettled {
                    currentOnExitSettled()
                }
            }

            else -> {
                enterPolicy.markRollback()
                exitPolicy.markRollback()
                heightFraction.snapTo(1f)
                alpha.snapTo(1f)
            }
        }
    }

    ListItemPhaseDisposalEffect(
        activeDirection,
        enterPolicy,
        exitPolicy,
        currentOnEnterSettled,
        currentOnExitSettled,
    )

    return this
        .graphicsLayer {
            this.alpha = if (isHidden) 0f else alpha.value
            this.compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        .clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val fraction = if (isHidden) 0f else heightFraction.value
            val animatedHeight = (placeable.height * fraction).roundToInt()
            layout(placeable.width, animatedHeight) {
                placeable.place(0, 0)
            }
        }
}

@Composable
private fun ListItemPhaseDisposalEffect(
    activeDirection: TwoPhaseDirection?,
    enterPolicy: TwoPhaseSettlePolicy,
    exitPolicy: TwoPhaseSettlePolicy,
    onEnterSettled: (() -> Unit)?,
    onExitSettled: () -> Unit,
) {
    val currentActiveDirection by rememberUpdatedState(activeDirection)
    val currentOnEnterSettled by rememberUpdatedState(onEnterSettled)
    val currentOnExitSettled by rememberUpdatedState(onExitSettled)
    DisposableEffect(Unit) {
        onDispose {
            when (currentActiveDirection) {
                TwoPhaseDirection.ENTER ->
                    enterPolicy.markDisposed(isActive = true) {
                        currentOnEnterSettled?.invoke()
                    }

                TwoPhaseDirection.EXIT ->
                    exitPolicy.markDisposed(isActive = true) {
                        currentOnExitSettled()
                    }

                null -> Unit
            }
        }
    }
}

internal suspend fun runListItemPhaseAnimation(
    direction: TwoPhaseDirection,
    alpha: Animatable<Float, *>,
    heightFraction: Animatable<Float, *>,
    motionSpecs: LomoListItemMotionSpecs,
) {
    for (step in TwoPhaseAnimationPlanner.plan(direction)) {
        when (step.property) {
            TwoPhaseProperty.ALPHA ->
                alpha.animateTo(
                    targetValue = step.targetValue,
                    animationSpec = when (direction) {
                        TwoPhaseDirection.ENTER -> motionSpecs.fadeInSpec
                        TwoPhaseDirection.EXIT -> motionSpecs.fadeOutSpec
                    },
                )

            TwoPhaseProperty.HEIGHT_FRACTION ->
                heightFraction.animateTo(
                    targetValue = step.targetValue,
                    animationSpec = motionSpecs.heightFractionSpec,
                )
        }
    }
}
