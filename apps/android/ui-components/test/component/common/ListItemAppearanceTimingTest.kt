package com.lomo.ui.component.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.unit.IntOffset
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: a committed row opens its gap before fading in; owner: ui-components; P1.
 * Scenarios:
 * - Given an entering row, when height is still growing, then alpha remains zero; fading starts only after full height.
 * - Given an exiting row, when fading finishes, then height collapses afterwards.
 * Observable outcomes: real Animatable alpha/height samples and animation completion.
 * TDD proof: RED/GREEN: target/lomo/reports/manual/creation-start-latency/sequence-{red,green}.log.
 * Production scope: apps/android/ui-components/src/component/common/LomoListItemPhaseMotion.kt
 * Production scope: apps/android/ui-components/src/component/common/TwoPhaseAnimationPlanner.kt
 * Test Change Justification:
 * Reason category: explicit user correction restoring the established animation.
 * Old behavior/assertion being replaced: alpha increases while height is expanding.
 * Why old assertion is no longer correct: the user requires height expansion followed by fade;
 * the reported defect is the wait before that sequence starts, not the sequence itself.
 * Coverage preserved by: real frame samples verify both sequential enter and sequential exit.
 * Why this is not fitting the test to the implementation: it restores the user's explicit
 * choreography and fails against the mistakenly introduced concurrent implementation.
 * Excludes: storage writes, lazy layout, and physical-device performance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListItemAppearanceTimingTest :
    FunSpec({
        test("given a committed row when height expands then fading waits until expansion completes") {
            runTest {
                val frames = BroadcastFrameClock()
                val alpha = Animatable(0f)
                val height = Animatable(0f)
                val specs = LomoListItemMotionSpecs(tween(200), tween(200), tween<IntOffset>(400), tween(400))
                val animation = launch(frames) {
                    runListItemPhaseAnimation(TwoPhaseDirection.ENTER, alpha, height, specs)
                }
                runCurrent()
                frames.sendFrame(0L)
                runCurrent()
                frames.sendFrame(100_000_000L)
                runCurrent()
                (height.value > 0f && height.value < 1f) shouldBe true
                alpha.value shouldBe 0f
                frames.sendFrame(420_000_000L)
                runCurrent()
                height.value shouldBe 1f
                alpha.value shouldBe 0f
                frames.sendFrame(440_000_000L)
                runCurrent()
                frames.sendFrame(540_000_000L)
                runCurrent()
                (alpha.value > 0f && alpha.value < 1f) shouldBe true
                frames.sendFrame(700_000_000L)
                runCurrent()
                animation.isCompleted shouldBe true
                alpha.value shouldBe 1f
                height.value shouldBe 1f
            }
        }

        test("given a deleted row when fading then height remains until content has disappeared") {
            runTest {
                val frames = BroadcastFrameClock()
                val alpha = Animatable(1f)
                val height = Animatable(1f)
                val specs = LomoListItemMotionSpecs(tween(200), tween(200), tween<IntOffset>(400), tween(400))
                val animation = launch(frames) {
                    runListItemPhaseAnimation(TwoPhaseDirection.EXIT, alpha, height, specs)
                }
                suspend fun frame(nanos: Long) {
                    frames.sendFrame(nanos)
                    runCurrent()
                }
                runCurrent()
                frame(0L)
                frame(100_000_000L)
                (alpha.value > 0f && alpha.value < 1f) shouldBe true
                height.value shouldBe 1f
                frame(220_000_000L)
                alpha.value shouldBe 0f
                frame(240_000_000L)
                frame(340_000_000L)
                (height.value > 0f && height.value < 1f) shouldBe true
                frame(700_000_000L)
                animation.isCompleted shouldBe true
                height.value shouldBe 0f
            }
        }
    })
