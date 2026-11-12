package com.lomo.ui.component.common

import androidx.compose.runtime.BroadcastFrameClock
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: modal sheet entry waits for real content layout and its following frame;
 * owner: ui-components rendering lifecycle; priority: P1.
 * Scenarios:
 * Given unmeasured or zero-height content, when frames arrive, then entry remains blocked.
 * Given measured content, when the next frame arrives, then expansion becomes admissible.
 * Given dismissal before that frame, when the entry coroutine is cancelled, then no delayed
 * expansion survives into another sheet.
 * Observable outcomes: suspended entry completion, expansion admission and cancellation.
 * TDD proof: RED with immediate entry (the previous ModalBottomSheet show timing); GREEN with
 * the measured-content frame barrier. Command: ./kotlin test --include-module=ui-components
 * --include-classes='com.lomo.ui.component.common.ModalSheetEntryTest'.
 * Excludes: GPU frame deadlines and Android window creation; device traces measure those paths.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModalSheetEntryTest : UiComponentsFunSpec() {
    init {
        test("given cold content when frames precede measurement then entry waits for a measured frame") {
            runTest {
                val frames = BroadcastFrameClock()
                val entry = ModalSheetEntry()
                val opening = launch(frames) { entry.awaitContentFrame() }
                runCurrent()
                frames.sendFrame(1L)
                runCurrent()
                entry.canExpand shouldBe false
                opening.isCompleted shouldBe false
                entry.onContentMeasured(0)
                frames.sendFrame(2L)
                runCurrent()
                entry.canExpand shouldBe false
                entry.onContentMeasured(600)
                runCurrent()
                entry.canExpand shouldBe false
                frames.sendFrame(3L)
                runCurrent()
                entry.canExpand shouldBe true
                opening.isCompleted shouldBe true
            }
        }

        test("given dismissal after layout when the next frame arrives then the cancelled entry stays closed") {
            runTest {
                val frames = BroadcastFrameClock()
                val entry = ModalSheetEntry()
                val opening = launch(frames) { entry.awaitContentFrame() }
                entry.onContentMeasured(600)
                runCurrent()
                opening.cancel()
                runCurrent()
                frames.sendFrame(1L)
                runCurrent()
                opening.isCancelled shouldBe true
                entry.canExpand shouldBe false
            }
        }
    }
}
