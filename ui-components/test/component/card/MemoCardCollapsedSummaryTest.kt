package com.lomo.ui.component.card

import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.component.markdown.MarkdownPresentationPolicy
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: buildMemoCardCollapsedSummary
 * - Owning layer: ui-components
 * - Priority tier: P2
 * - Capability: build clean collapsed summary text from presentation plan omitting inline tags and reminders.
 *
 * Scenarios:
 * - Given markdown document with tags and reminders, when collapsed summary is built, then tags and reminders are omitted.
 *
 * Observable outcomes:
 * - clean summary text without tag or reminder noise.
 *
 * TDD proof:
 * - Red before presentation plan integration: summary included raw tag/reminder text spans.
 *
 * Excludes:
 * - Compose card layout, full document rendering.
 */
class MemoCardCollapsedSummaryTest : UiComponentsFunSpec() {
    init {
        test("given markdown document with tags and reminders when collapsed summary is built then tags and reminders are omitted") {
            val document =
                MarkdownRenderDocument(
                    sourceByteLength = 80uL,
                    plainText = "Task item #work @2026-09-02-12:00 done",
                    tagNames = listOf("work"),
                    attachmentDestinations = emptyList(),
                    blocks =
                        listOf(
                            MarkdownRenderBlock.Paragraph(
                                sourceSpan = MarkdownSourceSpan(0uL, 80uL),
                                inlines =
                                    listOf(
                                        MarkdownRenderInline.Text(MarkdownSourceSpan(0uL, 10uL), "Task item "),
                                        MarkdownRenderInline.Tag(MarkdownSourceSpan(10uL, 15uL), "work"),
                                        MarkdownRenderInline.Text(MarkdownSourceSpan(15uL, 16uL), " "),
                                        MarkdownRenderInline.Reminder(MarkdownSourceSpan(16uL, 34uL), "@2026-09-02-12:00"),
                                        MarkdownRenderInline.Text(MarkdownSourceSpan(34uL, 39uL), " done"),
                                    ),
                            ),
                        ),
                )

            val summary = buildMemoCardCollapsedSummary(document = document, policy = MarkdownPresentationPolicy.MEMO_CARD)

            summary shouldBe "Task item done"
        }
    }
}
