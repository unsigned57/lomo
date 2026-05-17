package com.lomo.ui.component.card

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: memo-card collapsed preview mode policy.
 * - Behavior focus: long collapsed memo previews stay visually close to expanded markdown by preferring a markdown preview path, while still falling back to summary text only when markdown content is unavailable.
 * - Observable outcomes: selected collapsed-preview mode for collapsed, expanded, and markdown-empty cases.
 * - Red phase: Fails before the fix because collapsed memo cards always choose the summary path whenever summary text exists, so long-form previews diverge from expanded markdown rhythm.
 * - Excludes: Compose card composition, markdown parsing, TextView paragraph rendering, and animation runtime behavior.
 */
class MemoCardCollapsedSummaryPolicyTest : UiComponentsFunSpec() {
    init {
        test("collapsed long memo preview prefers markdown path when markdown content exists") {
        (resolveMemoCardCollapsedPreviewMode(
                isCollapsedPreview = true,
                hasProcessedContent = true,
                collapsedSummary = "preview body",
            )) shouldBe (MemoCardCollapsedPreviewMode.MarkdownPreview)
        }
    }

    init {
        test("summary fallback stays available only when collapsed preview has no markdown content") {
        (resolveMemoCardCollapsedPreviewMode(
                isCollapsedPreview = true,
                hasProcessedContent = false,
                collapsedSummary = "preview body",
            )) shouldBe (MemoCardCollapsedPreviewMode.Summary)
        }
    }

    init {
        test("expanded preview never stays on a collapsed rendering mode") {
        (resolveMemoCardCollapsedPreviewMode(
                isCollapsedPreview = false,
                hasProcessedContent = true,
                collapsedSummary = "preview body",
            )) shouldBe (MemoCardCollapsedPreviewMode.FullContent)
        (resolveMemoCardCollapsedPreviewMode(
                isCollapsedPreview = true,
                hasProcessedContent = false,
                collapsedSummary = "",
            )) shouldBe (MemoCardCollapsedPreviewMode.FullContent)
        }
    }
}
