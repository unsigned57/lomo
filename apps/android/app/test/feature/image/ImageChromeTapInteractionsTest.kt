package com.lomo.app.feature.image

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: image chrome responds on release while zoom retains double taps; owner: app; P1.
 * Scenarios:
 * - Given a released tap, when received, then chrome toggles without advancing virtual time.
 * - Given two nearby taps within the double-tap window, then chrome toggles only once.
 * - Given cancelled gestures or a long press, then chrome does not toggle.
 * - Given a later tap or a distant tap, then a new chrome toggle is accepted.
 * Observable outcomes: chrome visibility transitions and event delivery time.
 * TDD proof: upstream 0.19.0 detectTapAndQuickZoomGestures waits for awaitSecondDown before
 * onTap; regression tests exercise the replacement release stream without that timeout.
 * GREEN: ui-bugs/image-taps-green.log. This host test does not claim an instrumented RED.
 * Excludes: Telephoto gesture recognition, device frame timing and pinch rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageChromeTapInteractionsTest : AppFunSpec() {
    init {
        test("given a tap then release toggles immediately and a nearby second tap belongs to zoom") {
            runTest {
                val source = MutableInteractionSource()
                var taps = 0
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    collectImageChromeTaps(source, 300L, 500L, 48f, { testScheduler.currentTime }) { taps++ }
                }
                val first = PressInteraction.Press(Offset.Zero)
                source.emit(first)
                source.emit(PressInteraction.Release(first))
                taps shouldBe 1
                testScheduler.currentTime shouldBe 0L
                testScheduler.advanceTimeBy(80L)
                val second = PressInteraction.Press(Offset(2f, 2f))
                source.emit(second)
                source.emit(PressInteraction.Release(second))
                taps shouldBe 1
                testScheduler.advanceTimeBy(301L)
                val third = PressInteraction.Press(Offset.Zero)
                source.emit(third)
                source.emit(PressInteraction.Release(third))
                taps shouldBe 2
                val distant = PressInteraction.Press(Offset(200f, 200f))
                source.emit(distant)
                source.emit(PressInteraction.Release(distant))
                taps shouldBe 3
            }
        }
        test("given a cancelled gesture or long press then no chrome toggle occurs") {
            runTest {
                val source = MutableInteractionSource()
                var taps = 0
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    collectImageChromeTaps(source, 300L, 500L, 48f, { testScheduler.currentTime }) { taps++ }
                }
                val drag = PressInteraction.Press(Offset.Zero)
                source.emit(drag)
                source.emit(PressInteraction.Cancel(drag))
                val hold = PressInteraction.Press(Offset.Zero)
                source.emit(hold)
                testScheduler.advanceTimeBy(501L)
                source.emit(PressInteraction.Release(hold))
                taps shouldBe 0
            }
        }
    }
}
