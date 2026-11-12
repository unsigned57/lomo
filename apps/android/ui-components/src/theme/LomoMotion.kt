package com.lomo.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale

/**
 * Lomo semantic motion layer.
 *
 * Call sites never pick a raw [MotionScheme] or hand-roll specs; they express intent:
 *
 * - [component]: expressive motion for small interactive elements (buttons, chips, switches,
 *   indicators, icon morphs). Springs may overshoot and shapes may morph.
 * - [layout]: standard motion for layout geometry (size, position, expansion). Springs never
 *   overshoot, so animated corners, heights and offsets stay inside their legal bounds.
 *
 * When the system animator duration scale is 0, spatial specs in both schemes become [snap];
 * effect specs keep their short fades so state changes remain legible.
 */
@Immutable
class LomoMotion internal constructor(val component: MotionScheme, val layout: MotionScheme)

/**
 * Resolves the ambient [LomoMotion]. Defaults to full expressive component motion plus standard
 * layout motion so previews and non-themed content keep working; [LomoTheme] provides the
 * reduced-motion-aware instance.
 */
val LocalLomoMotion: ProvidableCompositionLocal<LomoMotion> =
    compositionLocalOf { FullLomoMotion }

/**
 * Uses the same live duration-scale owner as Compose animations. Reading its snapshot-backed
 * scale during composition restores the spatial scheme when animations are re-enabled.
 * Preview compositions without a platform scale use the full scheme.
 */
@Composable
fun rememberLomoMotion(): LomoMotion {
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return lomoMotionForDurationScale(durationScale)
}

internal fun lomoMotionForDurationScale(durationScale: MotionDurationScale?): LomoMotion =
    if (durationScale?.scaleFactor == 0f) SpatialSnappingLomoMotion else FullLomoMotion

/** Keep lazy-item coordinates current while measured row height owns the movement. */
internal fun <T> followAnimatedLayoutSpec(): FiniteAnimationSpec<T> = snap()

private val FullLomoMotion: LomoMotion =
    LomoMotion(
        component = MotionScheme.expressive(),
        layout = MotionScheme.standard(),
    )

private val SpatialSnappingLomoMotion: LomoMotion =
    LomoMotion(
        component = MotionScheme.expressive().withSnappingSpatialSpecs(),
        layout = MotionScheme.standard().withSnappingSpatialSpecs(),
    )

@Immutable
private class SpatialSnappingMotionScheme(private val delegate: MotionScheme) : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = delegate.defaultEffectsSpec()

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = delegate.fastEffectsSpec()

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = delegate.slowEffectsSpec()
}

private fun MotionScheme.withSnappingSpatialSpecs(): MotionScheme = SpatialSnappingMotionScheme(this)
