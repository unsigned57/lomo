package com.lomo.ui.text

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: resolveMemoParagraphTapOutcome — the decision function that turns a
 *   tap on a memo paragraph into one of {open link, clear scope selection, invoke body
 *   click, ignore}.
 *
 * Scenarios:
 * - Happy: tap with active selection clears selection.
 * - Boundary: tap on a link while selection is active clears selection instead of opening.
 * - Failure: tap with no selection and no link falls back to InvokeBodyClick.
 * - Must-not-happen: tap never opens a link when selection is active.
 *
 * Behavior focus: in free-copy mode the selection is shared across paragraphs via the
 *   selection scope. A tap on **any** paragraph while a cross-paragraph selection is live
 *   must dismiss the selection — not only when the paragraph the tap landed on happens to
 *   be part of the highlighted range. Otherwise users on expanded memos can no longer
 *   cancel a selection by tapping on the body (each paragraph block sees its own
 *   `selectionState.hasSelection = false` and falls through to `onBodyClick`).
 * - Observable outcomes: the returned MemoParagraphTapOutcome for each combination of
 *   (link, paragraphHasSelection, scopeHasSelection).
 * - TDD proof: Fails before the fix because the live decision lives inline inside
 *   `memoParagraphPointerInput` and only inspects the paragraph-local selectionState; the
 *   pure decision function this test pins does not exist yet.
 * - Excludes: link URL handling, gesture detection mechanics, body click invocation.
 */
class MemoParagraphTapOutcomeTest : UiComponentsFunSpec() {
    init {
        val link = MemoTextLinkRange(start = 0, end = 5, url = "https://example.com")

        test("tap on a paragraph that owns the selection clears the scope selection") {
            val outcome =
                resolveMemoParagraphTapOutcome(
                    link = null,
                    paragraphHasSelection = true,
                    scopeHasSelection = true,
                )

            outcome shouldBe MemoParagraphTapOutcome.ClearSelection
        }

        test("tap on a paragraph not in the selection still clears the scope when scope holds a selection") {
            val outcome =
                resolveMemoParagraphTapOutcome(
                    link = null,
                    paragraphHasSelection = false,
                    scopeHasSelection = true,
                )

            outcome shouldBe MemoParagraphTapOutcome.ClearSelection
        }

        test("tap with no selection and no link invokes the body click") {
            val outcome =
                resolveMemoParagraphTapOutcome(
                    link = null,
                    paragraphHasSelection = false,
                    scopeHasSelection = false,
                )

            outcome shouldBe MemoParagraphTapOutcome.InvokeBodyClick
        }

        test("tap on a link with no active selection opens the link") {
            val outcome =
                resolveMemoParagraphTapOutcome(
                    link = link,
                    paragraphHasSelection = false,
                    scopeHasSelection = false,
                )

            outcome shouldBe MemoParagraphTapOutcome.OpenLink(link.url)
        }

        test("tap on a link is suppressed while any selection is active so users can dismiss first") {
            val outcomeLocal =
                resolveMemoParagraphTapOutcome(
                    link = link,
                    paragraphHasSelection = true,
                    scopeHasSelection = true,
                )
            val outcomeOtherBlock =
                resolveMemoParagraphTapOutcome(
                    link = link,
                    paragraphHasSelection = false,
                    scopeHasSelection = true,
                )

            outcomeLocal shouldBe MemoParagraphTapOutcome.ClearSelection
            outcomeOtherBlock shouldBe MemoParagraphTapOutcome.ClearSelection
        }
    }
}
