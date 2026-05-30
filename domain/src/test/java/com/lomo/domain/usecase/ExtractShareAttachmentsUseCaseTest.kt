/*
 * Behavior Contract:
 * - Unit under test: ExtractShareAttachmentsUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: provide share attachment paths from the domain-owned memo content analyzer.
 *
 * Scenarios:
 * - Given markdown, wiki, and audio attachments, when share attachments are extracted, then local
 *   attachment paths and URI identities are returned while remote references are excluded.
 * - Given wiki and markdown image targets in mixed source order, when share attachments are
 *   extracted, then image path order follows MemoContentAnalyzer.imageUrls.
 * - Given duplicate local targets, when share attachments are extracted, then each local path is
 *   emitted once.
 *
 * Observable outcomes:
 * - ShareAttachmentExtractionResult.localAttachmentPaths and attachmentUris.
 *
 * TDD proof:
 * - RED: before the fix, mixed wiki/markdown image order is produced by use-case-local regex groups
 *   instead of MemoContentAnalyzer.imageUrls.
 *
 * Excludes:
 * - transport encoding, filesystem resolution, and app share orchestration.
 */
package com.lomo.domain.usecase

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

class ExtractShareAttachmentsUseCaseTest : DomainFunSpec() {
    private val useCase = ExtractShareAttachmentsUseCase()
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

            val result = useCase(content)

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

            val result = useCase(content)

            result.localAttachmentPaths shouldBe
                listOf("vault/first.jpg", "images/second.png", "voice/recording.m4a")
        }

        test("extracts distinct local paths only") {
            val content =
                """
                ![img]( ./same.png )
                ![img2](./same.png)
                """.trimIndent()

            val result = useCase(content)

            result.localAttachmentPaths shouldBe listOf("./same.png")
        }
    }
}
