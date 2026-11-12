package com.lomo.ui.component.common

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.IntOffset
import com.lomo.ui.theme.lomoMotionForDurationScale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Capability: preserve real row motion and position tracking through a height-driven list mutation;
 * owning layer: ui-components; priority: P1.
 * Scenarios:
 * - Given measured height animates, when layout measures each frame, then neighbor placement follows each measured position directly
 *   while row height and fades retain progressive nonzero-duration motion.
 * - Given ordinary reordering, when placement is sampled, then placement still has intermediate positions.
 * - Given duration scale changes 0 -> 1 -> 0, when motion resolves, then the selected scheme follows that live owner.
 * - Given a preview without a platform scale, when motion resolves, then the full spatial scheme is available.
 * Observable outcomes: sampled animation positions, durations and progressive height/alpha values.
 * TDD proof: actual device RED at scale=1: the survivor moved 842 -> 378 -> 1413 -> 378;
 * target/lomo/reports/manual/list-motion/delete-real-red-trajectory.json and delete-real-motion.mp4.
 * Previous zero-scale videos do not prove spatial motion. GREEN requires both these specs and
 * a new scale=1 device trajectory with intermediate positions and no reverse displacement.
 * Excludes: persistence and physical-device performance.
 */
class LomoListItemMotionContinuityTest :
    FunSpec({
        test("given row height owns movement then placement follows layout while height and effects still animate") {
            val layout = lomoMotionForDurationScale(null).layout
            val specs = LomoListItemMotionSpecs(
                fadeInSpec = layout.defaultEffectsSpec(),
                fadeOutSpec = layout.fastEffectsSpec(),
                placementSpec = layout.defaultSpatialSpec(),
                heightFractionSpec = layout.defaultSpatialSpec(),
            )
            val follow = TargetBasedAnimation(
                specs.placementFor(animatePlacement = false),
                IntOffset.VectorConverter,
                IntOffset(0, 842),
                IntOffset(0, 378),
            )
            follow.getValueFromNanos(0L) shouldBe IntOffset(0, 378)
            follow.durationNanos shouldBe 0L
            val height = TargetBasedAnimation(specs.heightFractionSpec, Float.VectorConverter, 1f, 0f)
            val heightSamples = (1..7).map { height.getValueFromNanos(height.durationNanos * it / 8) }
            (height.durationNanos > 0L) shouldBe true
            (heightSamples.count { it > 0f && it < 1f } >= 3) shouldBe true
            heightSamples.zipWithNext().all { (before, after) -> after <= before } shouldBe true
            for (spec in listOf(specs.fadeInSpec, specs.fadeOutSpec)) {
                val fade = TargetBasedAnimation(spec, Float.VectorConverter, 0f, 1f)
                (fade.durationNanos > 0L) shouldBe true
                val middle = fade.getValueFromNanos(fade.durationNanos / 2)
                (middle > 0f && middle < 1f) shouldBe true
            }
            val reorder = TargetBasedAnimation(
                specs.placementFor(animatePlacement = true),
                IntOffset.VectorConverter,
                IntOffset(0, 842),
                IntOffset(0, 378),
            )
            (reorder.durationNanos > 0L) shouldBe true
            (reorder.getValueFromNanos(reorder.durationNanos / 4).y in 379..841) shouldBe true
        }

        test("given the live duration scale changes then spatial animation restores without a process restart") {
            val scale = object : MotionDurationScale {
                override var scaleFactor = 0f
            }
            fun duration() = TargetBasedAnimation(
                lomoMotionForDurationScale(scale).layout.defaultSpatialSpec<Float>(),
                Float.VectorConverter,
                0f,
                1f,
            ).durationNanos
            duration() shouldBe 0L
            scale.scaleFactor = 1f
            (duration() > 0L) shouldBe true
            scale.scaleFactor = 0f
            duration() shouldBe 0L
        }
    })
