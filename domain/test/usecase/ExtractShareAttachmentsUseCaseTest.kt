/*
 * Behavior Contract:
 * - Unit under test: ExtractShareAttachmentsUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: filter Rust-projected attachment destinations for local sharing.
 *
 * Scenarios:
 * - Given typed local, remote, image, and audio destinations, when share attachments are extracted,
 *   then local paths and URI identities are returned in owner-projected order.
 * - Given duplicate local targets, when share attachments are extracted, then each local path is
 *   emitted once.
 *
 * Observable outcomes:
 * - ShareAttachmentExtractionResult.localAttachmentPaths and attachmentUris.
 *
 * TDD proof:
 * - RED: before the fix, the use case reparses raw Markdown instead of consuming owner projections.
 *
 * Excludes:
 * - transport encoding, filesystem resolution, and app share orchestration.
 
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
package com.lomo.domain.usecase

import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import io.kotest.matchers.shouldBe

class ExtractShareAttachmentsUseCaseTest : DomainFunSpec() {
    init {
        test("extracts markdown wiki and audio local attachments while filtering remote") {
            val content =
                """
                ![img](images/a.png)
                ![[vault/b.jpg|cover]]
                [voice](voice/recording.m4a)
                ![remote](https://cdn.example.com/c.png)
                [remote-audio](http://example.com/d.mp3)
                """.trimIndent()

            val result =
                useCase(
                    "images/a.png",
                    "vault/b.jpg",
                    "voice/recording.m4a",
                    "https://cdn.example.com/c.png",
                    "http://example.com/d.mp3",
                )(content)

            result.localAttachmentPaths shouldBe listOf("images/a.png", "vault/b.jpg", "voice/recording.m4a")
            result.attachmentUris shouldBe mapOf(
                    "images/a.png" to "images/a.png",
                    "vault/b.jpg" to "vault/b.jpg",
                    "voice/recording.m4a" to "voice/recording.m4a",
                )
        }

        test("given mixed image syntaxes when extracting share attachments then image order follows content analyzer") {
            val content =
                """
                ![[vault/first.jpg|cover]]
                ![second](images/second.png)
                [voice](voice/recording.m4a)
                """.trimIndent()

            val result =
                useCase("vault/first.jpg", "images/second.png", "voice/recording.m4a")(content)

            result.localAttachmentPaths shouldBe
                listOf("vault/first.jpg", "images/second.png", "voice/recording.m4a")
        }

        test("extracts distinct local paths only") {
            val content =
                """
                ![img]( ./same.png )
                ![img2](./same.png)
                """.trimIndent()

            val result = useCase("./same.png", "./same.png")(content)

            result.localAttachmentPaths shouldBe listOf("./same.png")
        }
    }

    private fun useCase(vararg attachments: String): ExtractShareAttachmentsUseCase =
        ExtractShareAttachmentsUseCase(FakeAttachmentMarkdownRepository(attachments.toList()))
}

private class FakeAttachmentMarkdownRepository(
    private val attachments: List<String>,
) : MarkdownWorkspaceRepository {
    override fun renderMarkdown(content: String): MarkdownRenderDocument =
        MarkdownRenderDocument(
            sourceByteLength = content.encodeToByteArray().size.toULong(),
            plainText = "",
            tagNames = emptyList(),
            attachmentDestinations = attachments,
            blocks = emptyList(),
        )

    override suspend fun toggleTask(
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): String = error("toggle not expected")
}
