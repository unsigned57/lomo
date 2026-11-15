package com.lomo.ui.component.menu

/*
 * Behavior Contract:
 * - Unit under test: RadialOverlayVisibilityState, runRadialOverlayTransition
 * - Owning layer: ui-components
 * - Priority tier: P1
 * - Production scope: tests radial sub-action overlay lifecycle state and frame-clocked exit
 *   transition mechanics in JVM unit tests without Robolectric Popup window hosting.
 * - Capability: Dismissing the radial sub-action overlay animates spatial and effect properties
 *   smoothly back to rest before teardown, blocking duplicate dismisses or item taps while exiting.
 *
 * Scenarios:
 * - Given an open overlay group, when dismissal is requested, then activeGroup stays mounted,
 *   targetVisible becomes false, canInteract becomes false, and a second dismiss request is ignored
 *   until completeDismiss runs.
 * - Given an in-flight dismiss whose completion token is captured, when another group opens before
 *   the token completes, then the stale completion does not tear down the new active group.
 * - Given settled spatial/effect values at 1f under normal duration scale, when transitioning to
 *   visible = false across frame ticks, then both values animate gradually through intermediate
 *   fractions before reaching 0f and invoking onDismissSettled once at the end.
 * - Given an in-flight reveal interrupted mid-flight by visible = false, when frame ticks advance,
 *   then spatialProgress reverses smoothly from its mid-flight value down to 0f before settling.
 * - Given animator duration scale 0f, when transitioning to visible = false, then both values settle
 *   at 0f and invoke onDismissSettled once without stalling.
 * Observable outcomes: mounted group, interaction state, animation fractions, and settlement count.
 * TDD proof: original RED execution is not recorded in this snapshot; verify the existing scenarios
 * with `./kotlin test --include-module=ui-components --include-classes='com.lomo.ui.component.menu.RadialOverlayExitAnimationTest'`.
 * Excludes: Popup window hosting, Android frame delivery, and rendered blur appearance.
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import com.lomo.ui.testing.UiComponentsFunSpec
import com.lomo.ui.theme.lomoMotionForDurationScale
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val FRAME_STEP_NANOS = 16_000_000L

private fun fixedDurationScale(scale: Float): MotionDurationScale = object : MotionDurationScale {
    override val scaleFactor: Float = scale
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class RadialOverlayExitAnimationTest : UiComponentsFunSpec() {
    init {
        test("dismiss keeps activeGroup mounted and disables interaction until exit settles") {
            val state = RadialOverlayVisibilityState<String>()
            state.open("Share")

            state.activeGroup shouldBe "Share"
            state.targetVisible shouldBe true
            state.canInteract shouldBe true
            state.isDismissing shouldBe false

            val exitToken = state.requestDismiss()
            exitToken.shouldNotBeNull()
            state.activeGroup shouldBe "Share"
            state.targetVisible shouldBe false
            state.canInteract shouldBe false
            state.isDismissing shouldBe true

            state.requestDismiss() shouldBe null
            state.completeDismiss(exitToken)

            state.activeGroup shouldBe null
            state.targetVisible shouldBe false
            state.canInteract shouldBe false
            state.isDismissing shouldBe false
        }

        test("re-opening a group invalidates a stale exit completion token") {
            val state = RadialOverlayVisibilityState<String>()
            state.open("Share")
            val staleToken = state.requestDismiss()!!

            state.open("ShareAgain")
            state.completeDismiss(staleToken)

            state.activeGroup shouldBe "ShareAgain"
            state.targetVisible shouldBe true
            state.canInteract shouldBe true
        }

        test("exit transition animates gradually to 0f before firing settlement callback") {
            runTest {
                val frameClock = BroadcastFrameClock()
                val motion = lomoMotionForDurationScale(fixedDurationScale(1f)).component
                val spatial = Animatable(1f)
                val effects = Animatable(1f)
                var settledCount = 0

                withContext(frameClock) {
                    val job =
                        launch {
                            runRadialOverlayTransition(
                                visible = false,
                                spatialProgress = spatial,
                                effectsAlpha = effects,
                                motionScheme = motion,
                                onDismissSettled = { settledCount++ },
                            )
                        }
                    var frameNanos = 0L
                    yield()
                    frameClock.sendFrame(frameNanos)
                    yield()
                    frameNanos += FRAME_STEP_NANOS
                    frameClock.sendFrame(frameNanos)
                    yield()
                    frameNanos += FRAME_STEP_NANOS
                    frameClock.sendFrame(frameNanos)
                    yield()

                    settledCount shouldBe 0
                    spatial.value.shouldBeGreaterThan(0.01f)
                    spatial.value.shouldBeLessThan(0.99f)
                    effects.value.shouldBeLessThan(0.99f)

                    while (!job.isCompleted) {
                        frameNanos += FRAME_STEP_NANOS
                        frameClock.sendFrame(frameNanos)
                        yield()
                    }
                    job.join()
                }

                spatial.value shouldBe 0f
                effects.value shouldBe 0f
                settledCount shouldBe 1
            }
        }

        test("interrupting an open animation reverses smoothly from mid-flight before settling") {
            runTest {
                val frameClock = BroadcastFrameClock()
                val motion = lomoMotionForDurationScale(fixedDurationScale(1f)).component
                val spatial = Animatable(0f)
                val effects = Animatable(0f)
                var settledCount = 0

                withContext(frameClock) {
                    var frameNanos = 0L
                    val revealJob =
                        launch {
                            runRadialOverlayTransition(
                                visible = true,
                                spatialProgress = spatial,
                                effectsAlpha = effects,
                                motionScheme = motion,
                                onDismissSettled = { settledCount++ },
                            )
                        }
                    repeat(4) {
                        yield()
                        frameClock.sendFrame(frameNanos)
                        frameNanos += FRAME_STEP_NANOS
                    }
                    val midSpatial = spatial.value
                    midSpatial.shouldBeGreaterThan(0.01f)
                    midSpatial.shouldBeLessThan(0.99f)
                    revealJob.cancel()
                    revealJob.join()

                    val dismissJob =
                        launch {
                            runRadialOverlayTransition(
                                visible = false,
                                spatialProgress = spatial,
                                effectsAlpha = effects,
                                motionScheme = motion,
                                onDismissSettled = { settledCount++ },
                            )
                        }
                    repeat(6) {
                        yield()
                        frameClock.sendFrame(frameNanos)
                        frameNanos += FRAME_STEP_NANOS
                        yield()
                    }

                    settledCount shouldBe 0
                    spatial.value.shouldBeGreaterThan(0.0f)
                    spatial.value.shouldBeLessThan(midSpatial)

                    while (!dismissJob.isCompleted) {
                        frameNanos += FRAME_STEP_NANOS
                        frameClock.sendFrame(frameNanos)
                        yield()
                    }
                    dismissJob.join()
                }

                spatial.value shouldBe 0f
                effects.value shouldBe 0f
                settledCount shouldBe 1
            }
        }

        test("duration scale 0f settles exit transition cleanly without stalling") {
            runTest {
                val frameClock = BroadcastFrameClock()
                val motion = lomoMotionForDurationScale(fixedDurationScale(0f)).component
                val spatial = Animatable(1f)
                val effects = Animatable(1f)
                var settledCount = 0

                withContext(frameClock) {
                    val job =
                        launch {
                            runRadialOverlayTransition(
                                visible = false,
                                spatialProgress = spatial,
                                effectsAlpha = effects,
                                motionScheme = motion,
                                onDismissSettled = { settledCount++ },
                            )
                        }
                    var frameNanos = 0L
                    while (!job.isCompleted) {
                        yield()
                        frameClock.sendFrame(frameNanos)
                        frameNanos += FRAME_STEP_NANOS
                    }
                    job.join()
                }

                spatial.value shouldBe 0f
                effects.value shouldBe 0f
                settledCount shouldBe 1
            }
        }
    }
}
