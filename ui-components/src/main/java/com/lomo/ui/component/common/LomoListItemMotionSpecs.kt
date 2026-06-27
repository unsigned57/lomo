package com.lomo.ui.component.common

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lomo.ui.theme.MotionTokens

object LomoListItemMotionSpecs {
    const val EXIT_ANIMATION_DURATION_MILLIS = MotionTokens.DurationMedium2 * 2L

    val fadeInSpec: FiniteAnimationSpec<Float> = keyframes {
        durationMillis = MotionTokens.DurationLong2
        0f at 0
        1f at MotionTokens.DurationLong2 using MotionTokens.EasingEmphasizedDecelerate
    }

    val fadeOutSpec: FiniteAnimationSpec<Float> = keyframes {
        durationMillis = MotionTokens.DurationMedium2
        1f at 0
        0f at MotionTokens.DurationMedium2 using MotionTokens.EasingStandard
    }

    val placementSpec: FiniteAnimationSpec<IntOffset> = spring(
        stiffness = Spring.StiffnessLow,
        dampingRatio = Spring.DampingRatioNoBouncy
    )

    val collapseSpec: FiniteAnimationSpec<IntSize> = tween(
        durationMillis = MotionTokens.DurationMedium2,
        easing = MotionTokens.EasingStandard,
    )
}

/**
 * The shared list-item motion modifier: fade-in on appearance, spring placement when neighbors
 * shift, fade-out on disappearance.
 *
 * Set [animateAppearance] to false when the row's appearance is owned externally — e.g. a row
 * wrapped in [LomoListItemEnterScope], whose two-phase enter (expand then fade) must be the sole
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
