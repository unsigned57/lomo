package com.lomo.ui.component.common

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.theme.followAnimatedLayoutSpec

/**
 * Spec bundle for the memo list two-phase choreography, resolved from [LocalLomoMotion]'s
 * layout scheme so row motion never overshoots: fades use effect specs, the height-fraction
 * phase and neighbor placement use layout spatial specs, and a zero animator duration scale
 * snaps the spatial phases.
 *
 * Set [animateAppearance] to false when the row's appearance is owned externally — e.g. a row
 * using [lomoListItemPhaseMotion], whose two-phase enter (expand then fade) must be the sole
 * driver of the appearance. Placement and disappearance still apply so neighbors animate
 * normally.
 */
@Immutable
class LomoListItemMotionSpecs internal constructor(
    val fadeInSpec: FiniteAnimationSpec<Float>,
    val fadeOutSpec: FiniteAnimationSpec<Float>,
    val placementSpec: FiniteAnimationSpec<IntOffset>,
    val heightFractionSpec: FiniteAnimationSpec<Float>,
) {
    // Null removes the entire animateItem node when fades are also null. Keep its position
    // history current while measured row height owns the visible movement.
    private val followLayoutSpec = followAnimatedLayoutSpec<IntOffset>()

    internal fun placementFor(animatePlacement: Boolean): FiniteAnimationSpec<IntOffset> =
        if (animatePlacement) placementSpec else followLayoutSpec

    companion object {
        /**
         * Upper bound on the row exit choreography (fade phase plus collapse phase), used by
         * screens that delay empty-state rendering until exits have visually settled.
         */
        const val EXIT_ANIMATION_DURATION_MILLIS = 600L
    }
}

@Composable
internal fun rememberLomoListItemMotionSpecs(): LomoListItemMotionSpecs {
    val layout = LocalLomoMotion.current.layout
    return remember(layout) {
        LomoListItemMotionSpecs(
            fadeInSpec = layout.defaultEffectsSpec(),
            fadeOutSpec = layout.fastEffectsSpec(),
            placementSpec = layout.defaultSpatialSpec(),
            heightFractionSpec = layout.defaultSpatialSpec(),
        )
    }
}

/**
 * The shared list-item motion modifier: fade-in on appearance, no-bounce placement when
 * neighbors shift, fade-out on disappearance.
 */
@Composable
fun Modifier.lomoListItemMotion(
    scope: LazyItemScope,
    animateAppearance: Boolean = true,
    animatePlacement: Boolean = true,
): Modifier {
    val motionSpecs = rememberLomoListItemMotionSpecs()
    return with(scope) {
        animateItem(
            fadeInSpec = if (animateAppearance) motionSpecs.fadeInSpec else null,
            placementSpec = motionSpecs.placementFor(animatePlacement),
            fadeOutSpec = if (animateAppearance) motionSpecs.fadeOutSpec else null,
        )
    }
}
