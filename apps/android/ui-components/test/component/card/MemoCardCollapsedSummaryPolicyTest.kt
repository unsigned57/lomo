package com.lomo.ui.component.card

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given collapsed Markdown content, when preview mode is resolved, then Markdown presentation takes
 *   priority over summary fallback.
 * - Given expanded content or a collapsed preview without Markdown, when presentation is resolved, then
 *   expanded mode or the explicit summary fallback is selected.
 * - Unit under test: memo-card collapsed preview mode policy.
 * - Capability: long collapsed memo previews stay visually close to expanded markdown by preferring a markdown preview path, while still falling back to summary text only when markdown content is unavailable.
 * - Observable outcomes: selected collapsed-preview mode for collapsed, expanded, and markdown-empty cases.
 * - TDD proof: Fails before the fix because collapsed memo cards always choose the summary path whenever summary text exists, so long-form previews diverge from expanded markdown rhythm.
 * - Excludes: Compose card composition, markdown parsing, TextView paragraph rendering, and animation runtime behavior.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MemoCardCollapsedSummaryPolicy.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including collapsed long memo preview prefers markdown path
 * when markdown content exists; summary fallback stays available only when collapsed preview has no markdown
 * content.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MemoCardCollapsedSummaryPolicyTest : UiComponentsFunSpec() {
    init {
        test("collapsed long memo preview prefers markdown path when markdown content exists") {
            (
                resolveMemoCardCollapsedPreviewMode(
                    isCollapsedPreview = true,
                    hasProcessedContent = true,
                    collapsedSummary = "preview body",
                )
                ) shouldBe (MemoCardCollapsedPreviewMode.MarkdownPreview)
        }
    }

    init {
        test("summary fallback stays available only when collapsed preview has no markdown content") {
            (
                resolveMemoCardCollapsedPreviewMode(
                    isCollapsedPreview = true,
                    hasProcessedContent = false,
                    collapsedSummary = "preview body",
                )
                ) shouldBe (MemoCardCollapsedPreviewMode.Summary)
        }
    }

    init {
        test("expanded preview never stays on a collapsed rendering mode") {
            (
                resolveMemoCardCollapsedPreviewMode(
                    isCollapsedPreview = false,
                    hasProcessedContent = true,
                    collapsedSummary = "preview body",
                )
                ) shouldBe (MemoCardCollapsedPreviewMode.FullContent)
            (
                resolveMemoCardCollapsedPreviewMode(
                    isCollapsedPreview = true,
                    hasProcessedContent = false,
                    collapsedSummary = "",
                )
                ) shouldBe (MemoCardCollapsedPreviewMode.FullContent)
        }
    }
}
