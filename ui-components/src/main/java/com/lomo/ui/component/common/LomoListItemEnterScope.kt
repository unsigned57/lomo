package com.lomo.ui.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
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
 * A two-phase enter wrapper for lazy-list items — the symmetric counterpart of
 * [LomoListItemExitScope]. It expands height from 0 to full first (opening a gap that pushes
 * the rows below downward), then fades alpha 0→1, then calls [onEnterSettled]. This provides
 * the "push down first, then appear" ordering that `Modifier.animateItem` cannot express on
 * its own (it drives appearance fade and placement simultaneously).
 *
 * Behavior contract:
 * - Given isEntering = true on first composition, then the row starts collapsed and transparent
 *   (no flash of the full row).
 * - Given isEntering = true, when the scope composes, then height expands 0→full via
 *   expandVertically(collapseSpec); the rows below shift down to follow.
 * - Given height reached full, when the expand completes, then alpha animates 0→1 via fadeInSpec.
 * - Given alpha reached 1, when the fade completes, then onEnterSettled is invoked exactly once.
 * - Given isEntering = false (already settled / normal row), then the row is shown at full
 *   height and alpha with no animation.
 * - Given the composable leaves composition mid-enter, when onDispose fires, then onEnterSettled
 *   is invoked if not already settled (so the host clears the entering id).
 *
 * The row's own `Modifier.animateItem` appearance fade must be suppressed (see
 * [lomoListItemMotion]'s animateAppearance flag) so this scope is the sole driver of the
 * appearance.
 */
@Composable
fun LomoListItemEnterScope(
    isEntering: Boolean,
    onEnterSettled: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val alpha = remember { Animatable(if (isEntering) 0f else 1f) }
    val visibleState = remember { MutableTransitionState(!isEntering) }
    var settled by remember { mutableStateOf(false) }
    val currentOnEnterSettled by rememberUpdatedState(onEnterSettled)
    val currentIsEntering by rememberUpdatedState(isEntering)

    LaunchedEffect(isEntering) {
        if (isEntering) {
            settled = false
            visibleState.targetState = true
            snapshotFlow { visibleState.isIdle && visibleState.currentState }.first { it }
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = LomoListItemMotionSpecs.fadeInSpec,
            )
            if (!settled) {
                settled = true
                currentOnEnterSettled()
            }
        } else {
            settled = false
            visibleState.targetState = true
            alpha.snapTo(1f)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (currentIsEntering && !settled) {
                settled = true
                currentOnEnterSettled()
            }
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            this.compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = expandVertically(animationSpec = LomoListItemMotionSpecs.collapseSpec),
            exit = ExitTransition.None,
        ) {
            content()
        }
    }
}
