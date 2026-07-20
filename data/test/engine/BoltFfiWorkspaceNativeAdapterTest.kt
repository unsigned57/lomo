/*
 * Behavior Contract:
 * - Unit under test: BoltFfiWorkspaceNativeAdapter typed RenderDocument and scan boundary conversion.
 * - Owning layer: data (sole generated-binding adapter); Markdown semantics remain lomo-workspace.
 * - Priority tier: P0.
 * - Capability: reconstruct the nested domain Render IR from generated flat nodes without parsing Markdown.
 *
 * Scenarios:
 * - Given a valid v1 paragraph/tag hierarchy, when converted, then typed hierarchy/text/source byte
 *   span are preserved.
 * - Given link/image/task/table transport nodes, when converted, then their nested domain variants
 *   preserve destinations, action spans, header cells, and row grouping.
 * - Given an unknown schema or mismatched node count, when converted, then the boundary fails closed.
 * - Given an out-of-source span, excessive depth, or half-present action span, when converted, then
 *   the boundary fails closed instead of truncating or inventing a default.
 * - Given a scan summary with a typed exchange content reference, when converted, then the complete
 *   exact memo content is resolved and no preview/source fallback exists.
 * - Given typed reminder facts and identity in a scan summary, when converted, then every field is
 *   preserved for the session-owned query/rewrite boundary.
 *
 * Observable outcomes:
 * - Domain MarkdownRenderBlock/Inline fields, complete scan content, and structured boundary errors.
 *
 * TDD proof:
 * - RED before the fix: generated RenderDocument exposes typed nodes, while the adapter still expects
 *   blocksJson and has no validated typed-node snapshot surface.
 *
 * Excludes:
 * - Markdown recognition, Compose layout, generated binding internals, engine lifecycle.
 */
package com.lomo.data.engine

import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.nativebridge.RenderDocument
import com.lomo.nativebridge.RenderNode
import com.lomo.nativebridge.RenderNodeKind
import com.lomo.nativebridge.WorkspaceMemoContentReference
import com.lomo.nativebridge.WorkspaceMemoSummary
import com.lomo.nativebridge.WorkspaceScanPage
import com.lomo.nativebridge.WorkspaceReminderReference
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest

class BoltFfiWorkspaceNativeAdapterTest : FunSpec({
    test("given a valid typed tag node when converted then kind text and byte span are preserved") {
        val document =
            renderDocument(
                nodes =
                    listOf(
                        renderNode(kind = RenderNodeKind.PARAGRAPH, text = null, end = 7uL),
                        renderNode(kind = RenderNodeKind.TAG, text = "标签", end = 7uL, depth = 2u),
                    ),
            ).toDomainDocument(sourceContent = "#标签")

        val paragraph = document.blocks.single().shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
        paragraph.inlines.single() shouldBe
            MarkdownRenderInline.Tag(
                sourceSpan = MarkdownSourceSpan(startByte = 0uL, endByte = 7uL),
                name = "标签",
            )
    }

    test("given typed link image and task nodes when converted then nested interaction facts are preserved") {
        val document =
            renderDocument(
                nodes =
                    listOf(
                        renderNode(kind = RenderNodeKind.PARAGRAPH, text = null, end = 15uL),
                        renderNode(
                            kind = RenderNodeKind.LINK,
                            text = null,
                            destination = "https://lomo.app",
                            end = 7uL,
                            depth = 2u,
                        ),
                        renderNode(text = "Lomo", end = 4uL, depth = 3u),
                        renderNode(
                            kind = RenderNodeKind.IMAGE,
                            text = "cover",
                            destination = "images/cover.png",
                            end = 15uL,
                            depth = 2u,
                        ),
                        renderNode(kind = RenderNodeKind.LIST, text = null, end = 40uL),
                        renderNode(
                            kind = RenderNodeKind.LIST_ITEM,
                            text = null,
                            end = 40uL,
                            depth = 2u,
                            checked = false,
                            actionStart = 16uL,
                            actionEnd = 19uL,
                        ),
                        renderNode(kind = RenderNodeKind.PARAGRAPH, text = null, end = 40uL, depth = 3u),
                        renderNode(text = "task", end = 40uL, depth = 4u),
                    ),
            ).toDomainDocument(sourceContent = "x".repeat(40))

        val paragraph = document.blocks[0].shouldBeInstanceOf<MarkdownRenderBlock.Paragraph>()
        paragraph.inlines[0].shouldBeInstanceOf<MarkdownRenderInline.Link>().destination shouldBe
            "https://lomo.app"
        paragraph.inlines[1].shouldBeInstanceOf<MarkdownRenderInline.Image>().destination shouldBe
            "images/cover.png"
        val list = document.blocks[1].shouldBeInstanceOf<MarkdownRenderBlock.ListBlock>()
        list.items.single().actionSpan shouldBe MarkdownSourceSpan(16uL, 19uL)
        list.items.single().checked shouldBe false
    }

    test("given typed table cells when converted then header and contiguous rows are reconstructed") {
        val document =
            renderDocument(
                nodes =
                    listOf(
                        renderNode(kind = RenderNodeKind.TABLE, text = null, end = 12uL),
                        renderNode(kind = RenderNodeKind.TABLE_HEADER_CELL, text = null, end = 4uL, depth = 2u),
                        renderNode(text = "head", end = 4uL, depth = 3u),
                        renderNode(
                            kind = RenderNodeKind.TABLE_CELL,
                            text = null,
                            end = 12uL,
                            depth = 2u,
                            level = 0u,
                        ),
                        renderNode(text = "cell", end = 12uL, depth = 3u),
                    ),
            ).toDomainDocument(sourceContent = "x".repeat(12))

        val table = document.blocks.single().shouldBeInstanceOf<MarkdownRenderBlock.Table>()
        table.header.single().inlines.single().shouldBeInstanceOf<MarkdownRenderInline.Text>().text shouldBe "head"
        table.rows.single().single().inlines.single().shouldBeInstanceOf<MarkdownRenderInline.Text>().text shouldBe "cell"
    }

    test("given an unknown schema when converted then it fails closed") {
        val error =
            shouldThrow<WorkspaceRenderBoundaryException> {
                renderDocument(schemaVersion = 2u).toDomainDocument(sourceContent = "text")
            }

        error.code shouldBe "unknown_render_schema"
    }

    test("given a mismatched node count when converted then it fails closed") {
        val error =
            shouldThrow<WorkspaceRenderBoundaryException> {
                renderDocument(nodeCount = 3u).toDomainDocument(sourceContent = "text")
            }

        error.code shouldBe "render_node_count_mismatch"
    }

    test("given an out of source span when converted then it fails closed") {
        val error =
            shouldThrow<WorkspaceRenderBoundaryException> {
                renderDocument(
                    nodes = listOf(renderNode(kind = RenderNodeKind.PARAGRAPH, text = null, end = 5uL)),
                ).toDomainDocument(sourceContent = "x")
            }

        error.code shouldBe "render_span_out_of_bounds"
    }

    test("given excessive depth or incomplete action span when converted then it fails closed") {
        shouldThrow<WorkspaceRenderBoundaryException> {
            renderDocument(
                nodes = listOf(renderNode(kind = RenderNodeKind.PARAGRAPH, text = null, depth = 65u)),
            ).toDomainDocument(sourceContent = "text")
        }.code shouldBe "render_depth_out_of_bounds"

        shouldThrow<WorkspaceRenderBoundaryException> {
            renderDocument(
                nodes =
                    listOf(
                        renderNode(kind = RenderNodeKind.LIST, text = null),
                        renderNode(
                            kind = RenderNodeKind.LIST_ITEM,
                            depth = 2u,
                            text = null,
                            actionStart = 0uL,
                            actionEnd = null,
                        ),
                    ),
            ).toDomainDocument(sourceContent = "text")
        }.code shouldBe "render_action_span_incomplete"
    }

    test("given typed scan content reference when converted then full exact content is resolved") {
        val root = kotlin.io.path.createTempDirectory("lomo-scan-content").toFile()
        try {
            val resolver = ExchangeResolver(root)
            val content = "prefix-${"界🙂".repeat(180)}-suffix"
            val bytes = content.encodeToByteArray()
            resolver.resolveFile("ex.scope.memo-0").writeBytes(bytes)
            val page =
                WorkspaceScanPage(
                    items =
                        listOf(
                            WorkspaceMemoSummary(
                                path = "2024-01-01.md",
                                identity = "2024-01-01_10:00:00_0",
                                timePart = "10:00:00",
                                fingerprint = "a".repeat(64),
                                tags = listOf("tag"),
                                attachments = emptyList(),
                                reminders =
                                    listOf(
                                        WorkspaceReminderReference(
                                            opaqueId = "reminder-id",
                                            revision = "a".repeat(64),
                                            memoIdentity = "2024-01-01_10:00:00_0",
                                            sourceStart = 11uL,
                                            sourceEnd = 33uL,
                                            tokenFingerprint = "b".repeat(64),
                                            token = "@2024-01-01-09:30x2",
                                            dueAtLocal = "2024-01-01-09:30",
                                            repeatCount = 2u,
                                            firedCount = 0u,
                                            done = false,
                                            intervalMinutes = 10u,
                                            recurrenceCode = "",
                                        ),
                                    ),
                                hasTodo = false,
                                hasUrl = false,
                                content =
                                    WorkspaceMemoContentReference(
                                        exchangeToken = "ex.scope.memo-0",
                                        length = bytes.size.toULong(),
                                        digest = testSha256Hex(bytes),
                                    ),
                                bodyStart = 11uL,
                                bodyEnd = 12uL + bytes.size.toULong(),
                                startLine = 1u,
                                endLine = 2u,
                            ),
                        ),
                    nextCursor = null,
                )

            val snapshot = page.toSnapshot(resolver)

            snapshot.items.single().content shouldBe content
            snapshot.items.single().bodyStart shouldBe 11uL
            snapshot.items.single().reminders.single().opaqueId shouldBe "reminder-id"
            snapshot.items.single().reminders.single().dueAtLocal shouldBe "2024-01-01-09:30"
        } finally {
            root.deleteRecursively()
        }
    }
})

private fun testSha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun renderDocument(
    schemaVersion: UInt = 1u,
    nodes: List<RenderNode> =
        listOf(
            renderNode(kind = RenderNodeKind.PARAGRAPH, text = null),
            renderNode(depth = 2u),
        ),
    nodeCount: UInt = nodes.size.toUInt(),
): RenderDocument =
    RenderDocument(
        schemaVersion = schemaVersion,
        plainText = "text",
        nodeCount = nodeCount,
        tagNames = emptyList(),
        attachmentDestinations = emptyList(),
        nodes = nodes,
    )

private fun renderNode(
    kind: RenderNodeKind = RenderNodeKind.TEXT,
    end: ULong = 4uL,
    depth: UInt = 1u,
    text: String? = "text",
    destination: String? = null,
    level: UInt? = null,
    ordered: Boolean? = if (kind == RenderNodeKind.LIST) false else null,
    listStart: ULong? = if (kind == RenderNodeKind.LIST) 1uL else null,
    checked: Boolean? = null,
    actionStart: ULong? = null,
    actionEnd: ULong? = null,
): RenderNode =
    RenderNode(
        kind = kind,
        sourceStart = 0uL,
        sourceEnd = end,
        depth = depth,
        text = text,
        destination = destination,
        title = null,
        level = level,
        ordered = ordered,
        listStart = listStart,
        checked = checked,
        actionStart = actionStart,
        actionEnd = actionEnd,
    )
