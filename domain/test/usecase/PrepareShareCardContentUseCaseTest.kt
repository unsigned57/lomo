package com.lomo.domain.usecase

/*
 * Behavior Contract:
 * - Unit under test: PrepareShareCardContentUseCase
 * - Owning layer: production path under test
 * - Priority tier: P1
 * - Capability: preserve observable product behavior after Markdown semantic ownership moved to
 *   lomo-workspace (typed IR, workspace scan/render/document commands) with Kotlin adapters only.
 *
 * Scenarios:
 * - Given production collaborators expose workspace IR / document-command seams, when this suite
 *   runs, then assertions verify the same user-visible outcomes without Kotlin MarkdownParser.
 * - Given deleted JetBrains or line-authority helpers, when tests construct fakes, then they use
 *   FakeMarkdownWorkspace / content projector adapters instead of dual-authority parsers.
 * - Given invalid or missing readiness inputs, when exercised, then fail-closed outcomes remain.
 *
 * Observable outcomes:
 * - Public method results, DI wiring, and presentation fields match the post-cutover contracts.
 *
 * TDD proof:
 * - RED: suites fail to compile or assert against MarkdownParser / JetBrains plan types after cutover.
 * - GREEN: ./kotlin test on this class passes against workspace IR adapters.
 *
 * Excludes:
 * - Room schema ownership, sync backend redesign, and Compose pixel rendering.
 *
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */

import com.lomo.domain.model.ShareCardTextInput
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

class PrepareShareCardContentUseCaseTest : DomainFunSpec() {
    private val useCase =
        PrepareShareCardContentUseCase(
            FakeShareMarkdownRepository(),
        )

    init {
        test("invoke keeps long content without truncation") {
            val longContent = "x".repeat(4500)
            val result =
                useCase(
                    ShareCardTextInput(
                        content = longContent,
                        sourceTags = emptyList(),
                    ),
                )
            result.bodyText shouldBe longContent
            (result.bodyText.endsWith("...")) shouldBe false
        }

        test("invoke keeps all source tags without display truncation") {
            val tags =
                (1..8).map { index ->
                    "#feature_${"x".repeat(20)}_$index"
                }
            val result =
                useCase(
                    ShareCardTextInput(
                        content = "memo body",
                        sourceTags = tags,
                    ),
                )
            result.tags shouldBe tags.map { it.removePrefix("#") }
        }

        test("invoke uses owner plain text and tagNames when source tags empty") {
            val result =
                useCase(
                    ShareCardTextInput(
                        content = "line1  line2\n\n\nline3 #topic",
                        sourceTags = emptyList(),
                    ),
                )
            result.tags shouldBe listOf("topic")
            result.bodyText shouldBe "line1  line2\n\n\nline3"
        }
    }
}

private class FakeShareMarkdownRepository : MarkdownWorkspaceRepository {
    override fun renderMarkdown(content: String): MarkdownRenderDocument {
        val tags =
            Regex("""#([\p{L}\p{N}_/]+)""")
                .findAll(content)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        var plain = content
        tags.forEach { tag ->
            plain = plain.replace("#$tag", "").replace(Regex(""" {2,}"""), " ").trimEnd()
        }
        // Keep spacing for the explicit multi-space fixture by only removing tag tokens.
        plain =
            tags.fold(content) { acc, tag ->
                acc.replace(Regex("""(^|\s)#${Regex.escape(tag)}(?=\s|$)""")) { match ->
                    if (match.value.first().isWhitespace()) " " else ""
                }
            }.trimEnd()
        return MarkdownRenderDocument(
            sourceByteLength = content.encodeToByteArray().size.toULong(),
            plainText = plain.ifBlank { content },
            tagNames = tags,
            attachmentDestinations = emptyList(),
            blocks = emptyList(),
        )
    }

    override suspend fun toggleTask(
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): String = error("not expected")
}
