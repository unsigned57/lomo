package com.lomo.ui.component.common

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.CubicBezierEasing

/** The user-preserved memo insertion/deletion choreography is independent of the theme scheme. */
object LomoListItemMotionSpecs {
    private const val PHASE_DURATION_MILLIS = 300
    private const val ENTER_FADE_DURATION_MILLIS = 500
    private val phaseEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    private val enterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    const val EXIT_ANIMATION_DURATION_MILLIS = PHASE_DURATION_MILLIS * 2L

    val fadeInSpec: FiniteAnimationSpec<Float> = keyframes {
        durationMillis = ENTER_FADE_DURATION_MILLIS
        0f at 0
        1f at ENTER_FADE_DURATION_MILLIS using enterEasing
    }

    val fadeOutSpec: FiniteAnimationSpec<Float> = keyframes {
        durationMillis = PHASE_DURATION_MILLIS
        1f at 0
        0f at PHASE_DURATION_MILLIS using phaseEasing
    }

    val placementSpec: FiniteAnimationSpec<IntOffset> = spring(
        stiffness = Spring.StiffnessLow,
        dampingRatio = Spring.DampingRatioNoBouncy
    )

    val heightFractionSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = PHASE_DURATION_MILLIS,
        easing = phaseEasing,
    )
}

/**
 * The shared list-item motion modifier: fade-in on appearance, spring placement when neighbors
 * shift, fade-out on disappearance.
 *
 * Set [animateAppearance] to false when the row's appearance is owned externally — e.g. a row
 * using [lomoListItemPhaseMotion], whose two-phase enter (expand then fade) must be the sole
 * driver of the appearance. Placement and disappearance still apply so neighbors animate normally.
 */
fun Modifier.lomoListItemMotion(
    scope: LazyItemScope,
    animateAppearance: Boolean = true,
    animatePlacement: Boolean = true,
): Modifier = with(scope) {
    animateItem(
        fadeInSpec = if (animateAppearance) LomoListItemMotionSpecs.fadeInSpec else null,
        placementSpec = if (animatePlacement) LomoListItemMotionSpecs.placementSpec else null,
        fadeOutSpec = null
    )
}
