/*
 * Behavior Contract:
 * Capability: keep link-only memo bodies visible; owner ui-components; priority P1.
 * Scenarios:
 * - Given an empty link label, when projected, then its destination supplies visible clickable text.
 * - Given an authored label, when projected, then its label and destination remain intact.
 * Observable outcomes: visible text and Compose URL annotation ranges.
 * TDD proof: interaction-fixes/link-label-red.log and link-label-green.log in target/lomo/reports/manual.
 * Excludes: URI launching and upstream Markdown parsing.
 */
package com.lomo.ui.component.markdown

import androidx.compose.ui.text.LinkAnnotation
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

class MarkdownEmptyLinkLabelTest : UiComponentsFunSpec() {
    init {
        test("given empty web and location labels when projected then destinations remain visible and clickable") {
            listOf("https://example.com", "geo:31.2304,121.4737").forEach { destination ->
                val text = listOf(
                    MarkdownRenderInline.Link(MarkdownSourceSpan(0uL, 30uL), destination, null, emptyList()),
                )
                    .toAnnotatedText()
                text.text shouldBe destination
                text.getLinkAnnotations(0, text.length).single().item shouldBe LinkAnnotation.Url(destination)
            }
        }

        test("given an authored label when projected then label and destination are preserved") {
            val text = listOf(
                MarkdownRenderInline.Link(
                    MarkdownSourceSpan(0uL, 30uL),
                    "geo:31,121",
                    null,
                    listOf(MarkdownRenderInline.Text(MarkdownSourceSpan(1uL, 8uL), "Shanghai")),
                ),
            ).toAnnotatedText()
            text.text shouldBe "Shanghai"
            text.getLinkAnnotations(0, text.length).single().item shouldBe LinkAnnotation.Url("geo:31,121")
        }
    }
}
