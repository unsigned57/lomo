package com.lomo.ui.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.first

/**
 * A two-phase exit wrapper for lazy-list items: fades alpha to 0 first, then collapses
 * height to 0, then calls [onExitSettled]. This provides the "fade out, then collapse"
 * ordering that `Modifier.animateItem` cannot express on its own (it drives fade and
 * collapse simultaneously).
 *
 * Behavior contract:
 * - Given isExiting = true, when the scope composes, then alpha animates 1→0 via fadeOutSpec.
 * - Given alpha reached 0, when the fade completes, then height collapses to 0 via shrinkVertically(collapseSpec).
 * - Given height reached 0, when the collapse completes, then onExitSettled is
 *   invoked exactly once.
 * - Given isExiting = false (rollback), when the scope recomposes, then alpha
 *   restores to 1 and height restores, without calling onExitSettled.
 * - Given the composable leaves composition mid-exit (scrolled off-screen), when
 *   onDispose fires, then onExitSettled is invoked if not already settled and the index space is stable.
 *
 * The caller is responsible for keeping the item's key in the lazy list (via retention)
 * until onExitSettled fires; this scope only drives the visual phases.
 */
@Composable
fun LomoListItemExitScope(
    isExiting: Boolean,
    onExitSettled: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val alpha = remember { Animatable(1f) }
    val visibleState = remember { MutableTransitionState(true) }
    var settled by remember { mutableStateOf(false) }
    val currentOnExitSettled by rememberUpdatedState(onExitSettled)
    val currentIsExiting by rememberUpdatedState(isExiting)

    LaunchedEffect(isExiting) {
        if (isExiting) {
            settled = false
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = LomoListItemMotionSpecs.fadeOutSpec,
            )
            visibleState.targetState = false
            snapshotFlow { visibleState.currentState }.first { !it }
            if (!settled) {
                settled = true
                currentOnExitSettled()
            }
        } else {
            settled = false
            visibleState.targetState = true
            alpha.snapTo(1f)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (currentIsExiting && !settled) {
                settled = true
                currentOnExitSettled()
            }
        }
    }


    Box(
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha.value
                this.compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = EnterTransition.None,
            exit = shrinkVertically(animationSpec = LomoListItemMotionSpecs.collapseSpec),
        ) {
            content()
        }
    }
}
