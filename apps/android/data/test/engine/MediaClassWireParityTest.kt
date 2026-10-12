package com.lomo.data.engine

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.MediaClass
import com.lomo.nativebridge.RenderDocument
import com.lomo.nativebridge.RenderNode
import com.lomo.nativebridge.RenderNodeKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MediaClass wire→domain parity at the render boundary.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: every native MediaClass variant maps verbatim to the domain vocabulary; an image
 *   node missing its owner class fails the render boundary instead of being reclassified.
 *
 * Scenarios:
 * - Given the native enum, when enumerated against the domain enum, then names and order match —
 *   the mapping is total with no Kotlin-side reclassification.
 * - Given each native variant, when mapped, then the matching domain variant is produced.
 * - Given an IMAGE render node without mediaClass, when the document crosses the boundary, then
 *   the boundary fails with render_image_media_class_missing.
 * - Given an unknown wire value, when decoded, then fromValue throws — no silent classification.
 *
 * Observable outcomes:
 * - Enum parity, per-variant mapping, and the boundary failure code.
 *
 * TDD proof:
 * - RED before P-24: the wire carried no MediaClass and Kotlin classified by string prefixes.
 *
 * Excludes:
 * - JNI classifier calls (covered by lomo-workspace tests) and Compose rendering.
 */
class MediaClassWireParityTest : DataFunSpec() {
    init {
        test("given the wire enum when enumerated against the domain enum then names and order match") {
            com.lomo.nativebridge.MediaClass.entries.map { it.name } shouldContainExactly
                MediaClass.entries.map { it.name }
        }

        test("given each wire variant when mapped then the matching domain variant is produced") {
            com.lomo.nativebridge.MediaClass.entries.forEach { wire ->
                wire.toDomainMediaClass() shouldBe MediaClass.valueOf(wire.name)
            }
        }

        test("given an image node without owner class when the document crosses then the boundary fails") {
            val failure =
                shouldThrow<WorkspaceRenderBoundaryException> {
                    renderDocument(
                        attachments = emptyList(),
                        nodes = imageParagraph(mediaClass = null),
                    ).toDomainDocument("x")
                }

            failure.code shouldBe "render_image_media_class_missing"
        }

        test("given an image node with owner class when the document crosses then the class is verbatim") {
            val document =
                renderDocument(
                    attachments =
                        listOf(
                            com.lomo.nativebridge.MediaAttachment(
                                destination = "media/a.mp4",
                                mediaClass = com.lomo.nativebridge.MediaClass.VIDEO,
                            ),
                        ),
                    nodes = imageParagraph(mediaClass = com.lomo.nativebridge.MediaClass.VIDEO),
                ).toDomainDocument("x")

            document.attachments.single().mediaClass shouldBe MediaClass.VIDEO
            document.blocks.single()
                .let { it as com.lomo.domain.model.markdown.MarkdownRenderBlock.Paragraph }
                .inlines.single()
                .let { it as com.lomo.domain.model.markdown.MarkdownRenderInline.Image }
                .mediaClass shouldBe MediaClass.VIDEO
        }

        test("given an out-of-vocabulary wire value when decoded then fromValue throws explicitly") {
            shouldThrow<NoSuchElementException> {
                com.lomo.nativebridge.MediaClass.fromValue(99)
            }
        }
    }
}

private fun renderDocument(
    attachments: List<com.lomo.nativebridge.MediaAttachment>,
    nodes: List<RenderNode>,
): RenderDocument =
    RenderDocument(
        schemaVersion = 1u,
        plainText = "x",
        nodeCount = nodes.size.toUInt(),
        tagNames = emptyList(),
        attachments = attachments,
        nodes = nodes,
    )

private fun imageParagraph(mediaClass: com.lomo.nativebridge.MediaClass?): List<RenderNode> =
    listOf(
        RenderNode(
            kind = RenderNodeKind.PARAGRAPH,
            sourceStart = 0uL,
            sourceEnd = 1uL,
            depth = 1u,
            text = null,
            destination = null,
            title = null,
            level = null,
            ordered = null,
            listStart = null,
            checked = null,
            actionStart = null,
            actionEnd = null,
            mediaClass = null,
        ),
        imageNode(depth = 2u, mediaClass = mediaClass),
    )

private fun imageNode(
    depth: UInt,
    mediaClass: com.lomo.nativebridge.MediaClass?,
): RenderNode =
    RenderNode(
        kind = RenderNodeKind.IMAGE,
        sourceStart = 0uL,
        sourceEnd = 1uL,
        depth = depth,
        text = "alt",
        destination = "media/a.mp4",
        title = null,
        level = null,
        ordered = null,
        listStart = null,
        checked = null,
        actionStart = null,
        actionEnd = null,
        mediaClass = mediaClass,
    )
