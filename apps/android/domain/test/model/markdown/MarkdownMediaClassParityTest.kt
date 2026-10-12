package com.lomo.domain.model.markdown

import com.lomo.domain.model.MediaClass
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MediaClass vocabulary + MarkdownRenderDocument media projection.
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: the domain media vocabulary is the closed owner set {IMAGE, VIDEO, AUDIO, FILE},
 *   and content analysis buckets attachments strictly by the owner-projected class — never by
 *   extension strings.
 *
 * Scenarios:
 * - Given every MediaClass variant, when the domain vocabulary is enumerated, then exactly the
 *   owner set exists and no other variant is representable.
 * - Given one attachment per class, when content analysis is projected, then image lanes keep only
 *   IMAGE destinations and audio lanes keep only AUDIO destinations.
 *
 * Observable outcomes:
 * - Enum entries, imageUrls/audioUrls lists, hasAttachment flag.
 *
 * TDD proof:
 * - RED before P-24: MediaClass did not exist and buckets were extension-derived.
 *
 * Excludes:
 * - Wire enum mapping (data layer), native classifier calls, and UI rendering.
 */
class MarkdownMediaClassParityTest : DomainFunSpec() {
    init {
        test("given the domain media vocabulary when enumerated then it is exactly the owner set") {
            MediaClass.entries.map { it.name } shouldContainExactly
                listOf("IMAGE", "VIDEO", "AUDIO", "FILE")
        }

        test("given one attachment per owner class when analysis projects then lanes keep only their class") {
            val document =
                MarkdownRenderDocument(
                    sourceByteLength = 64uL,
                    plainText = "media",
                    tagNames = emptyList(),
                    attachments =
                        listOf(
                            MarkdownAttachment("images/a.png", MediaClass.IMAGE),
                            MarkdownAttachment("videos/b.mp4", MediaClass.VIDEO),
                            MarkdownAttachment("recordings/c.ogg", MediaClass.AUDIO),
                            MarkdownAttachment("files/d.bin", MediaClass.FILE),
                        ),
                    blocks = emptyList(),
                )

            val analysis = document.toMemoContentAnalysis()

            analysis.imageUrls shouldBe listOf("images/a.png")
            analysis.audioUrls shouldBe listOf("recordings/c.ogg")
            analysis.hasAttachment shouldBe true
        }
    }
}
