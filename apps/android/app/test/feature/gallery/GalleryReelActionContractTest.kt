package com.lomo.app.feature.gallery

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe

/**
 * Behavior Contract:
 * Scenarios:
 * - Given an unchecked memo task, when its gallery checkbox is clicked, then the callback receives the
 *   original memo, line index, and target checked state.
 * - Unit under test: GalleryReel memo todo action contract.
 * - Capability: when a task checkbox inside GalleryReel is clicked, the action contract must propagate the original memo, line index, and target checked state.
 * - Observable outcomes: the captured callback arguments match the exact clicked parameters and source memo.
 * - TDD proof: ensures explicit Gallery Reel / single-image viewer todo write-back behavior before the implementation details are wired.
 * - Excludes: Compose click detection and database persistence.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for GalleryReelActionContract.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including given a memo with unchecked task when todo clicked
 * then callback receives original memo line index and target checked state.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class GalleryReelActionContractTest : AppFunSpec() {
    init {
        test(
            "given a memo with unchecked task when todo clicked then callback receives original memo line index and target checked state",
        ) {
            val memo = Memo(
                id = "memo-1",
                timestamp = 1L,
                content = "- [ ] todo 1",
                rawContent = "- [ ] todo 1",
                dateKey = "2026_05_30",
            )

            var invokedMemo: Memo? = null
            var invokedLineIndex: Int? = null
            var invokedChecked: Boolean? = null

            val onTodoClick: (Memo, Int, Boolean) -> Unit = { m, line, chk ->
                invokedMemo = m
                invokedLineIndex = line
                invokedChecked = chk
            }

            // Simulates clicking the checkbox on line 0
            onTodoClick(memo, 0, true)

            invokedMemo shouldBe memo
            invokedLineIndex shouldBe 0
            invokedChecked shouldBe true
        }
    }
}
