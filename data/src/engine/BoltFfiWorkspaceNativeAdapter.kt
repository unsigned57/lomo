package com.lomo.data.engine

import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownRenderListItem
import com.lomo.domain.model.markdown.MarkdownRenderTableCell
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.nativebridge.RenderDocument
import com.lomo.nativebridge.RenderNode
import com.lomo.nativebridge.RenderNodeKind
import com.lomo.nativebridge.WorkspaceDocumentCommandKind
import com.lomo.nativebridge.WorkspaceReminderReference

internal fun WorkspaceNativeCommandSpec.toBridge(): WorkspaceDocumentCommandKind =
    when (this) {
        is WorkspaceNativeCommandSpec.Append ->
            WorkspaceDocumentCommandKind.Append(
                timePart = timePart,
                content = content,
            )
        is WorkspaceNativeCommandSpec.Replace ->
            WorkspaceDocumentCommandKind.Replace(
                identity = identity,
                content = content,
            )
        is WorkspaceNativeCommandSpec.Remove ->
            WorkspaceDocumentCommandKind.Remove(identity = identity)
        is WorkspaceNativeCommandSpec.ToggleTask ->
            WorkspaceDocumentCommandKind.ToggleTask(
                sourceStart = sourceStart,
                sourceEnd = sourceEnd,
            )
        is WorkspaceNativeCommandSpec.RewriteReminder ->
            WorkspaceDocumentCommandKind.RewriteReminder(
                reminder = reminder.toBridge(),
                replacement = replacement,
            )
    }

private fun WorkspaceReminderReferenceSnapshot.toBridge(): WorkspaceReminderReference =
    WorkspaceReminderReference(
        opaqueId = opaqueId,
        revision = revision,
        memoIdentity = memoIdentity,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        tokenFingerprint = tokenFingerprint,
        token = token,
        dueAtLocal = dueAtLocal,
        repeatCount = repeatCount,
        firedCount = firedCount,
        done = done,
        intervalMinutes = intervalMinutes,
        recurrenceCode = recurrenceCode,
    )

internal fun RenderDocument.toDomainDocument(sourceContent: String): MarkdownRenderDocument {
    requireRenderBoundary(schemaVersion == MarkdownRenderDocument.SCHEMA_VERSION, "unknown_render_schema") {
        "render schema must be v1"
    }
    requireRenderBoundary(
        nodes.size <= MarkdownRenderDocument.MAX_NODE_COUNT,
        "render_node_limit_exceeded",
    ) {
        "render node count exceeds ${MarkdownRenderDocument.MAX_NODE_COUNT}"
    }
    requireRenderBoundary(nodeCount == nodes.size.toUInt(), "render_node_count_mismatch") {
        "declared node count does not match typed node payload"
    }
    val sourceLength = sourceContent.encodeToByteArray().size.toULong()
    validateRenderString(plainText)
    tagNames.forEach(::validateRenderString)
    attachmentDestinations.forEach(::validateRenderString)
    val blocks = nodes.toRenderTrees(sourceLength).map(RenderTree::toBlock)
    val document =
        MarkdownRenderDocument(
        sourceByteLength = sourceLength,
        plainText = plainText,
        tagNames = tagNames,
        attachmentDestinations = attachmentDestinations,
        blocks = blocks,
        )
    requireRenderBoundary(document.nodeCount == nodeCount.toInt(), "render_tree_node_count_mismatch") {
        "nested render tree must preserve every transport node"
    }
    return document
}

private data class RenderTree(
    val node: RenderNode,
    val children: MutableList<RenderTree> = mutableListOf(),
)

private fun List<RenderNode>.toRenderTrees(sourceLength: ULong): List<RenderTree> {
    val roots = mutableListOf<RenderTree>()
    val stack = mutableListOf<RenderTree>()
    forEachIndexed { index, node ->
        node.validateTransportNode(sourceLength)
        val depth = node.depth.toInt()
        requireRenderBoundary(
            node.depth in 1u..MarkdownRenderDocument.MAX_NESTING_DEPTH.toUInt(),
            "render_depth_out_of_bounds",
        ) {
            "render node depth is outside 1..${MarkdownRenderDocument.MAX_NESTING_DEPTH}"
        }
        requireRenderBoundary(index != 0 || depth == 1, "render_root_depth_invalid") {
            "the first render node must have depth 1"
        }
        while (stack.size >= depth) stack.removeLast()
        requireRenderBoundary(stack.size == depth - 1, "render_depth_disconnected") {
            "render node depth cannot skip a parent level"
        }
        val tree = RenderTree(node)
        if (stack.isEmpty()) {
            roots += tree
        } else {
            stack.last().children += tree
        }
        stack += tree
    }
    return roots
}

private fun RenderNode.validateTransportNode(sourceLength: ULong) {
    validateSpan(sourceStart, sourceEnd, sourceLength, "render_span_out_of_bounds")
    listOfNotNull(text, destination, title).forEach(::validateRenderString)
    val actionStartValue = actionStart
    val actionEndValue = actionEnd
    requireRenderBoundary(
        (actionStartValue == null) == (actionEndValue == null),
        "render_action_span_incomplete",
    ) {
        "render action span must provide both start and end"
    }
    if (actionStartValue != null && actionEndValue != null) {
        validateSpan(actionStartValue, actionEndValue, sourceLength, "render_action_span_out_of_bounds")
        requireRenderBoundary(
            actionStartValue >= sourceStart && actionEndValue <= sourceEnd,
            "render_action_span_outside_node",
        ) {
            "render action span must be contained by its node span"
        }
    }
}

private fun RenderTree.toBlock(): MarkdownRenderBlock =
    when (node.kind) {
        RenderNodeKind.PARAGRAPH ->
            MarkdownRenderBlock.Paragraph(node.sourceSpan(), children.map(RenderTree::toInline))
        RenderNodeKind.HEADING ->
            MarkdownRenderBlock.Heading(
                sourceSpan = node.sourceSpan(),
                level = node.level.required("render_heading_level_missing", "heading level"),
                inlines = children.map(RenderTree::toInline),
            )
        RenderNodeKind.BLOCK_QUOTE ->
            MarkdownRenderBlock.BlockQuote(node.sourceSpan(), children.map(RenderTree::toBlock))
        RenderNodeKind.LIST ->
            MarkdownRenderBlock.ListBlock(
                sourceSpan = node.sourceSpan(),
                ordered = node.ordered.required("render_list_order_missing", "list ordered flag"),
                startNumber = node.listStart.required("render_list_start_missing", "list start"),
                items = children.map(RenderTree::toListItem),
            )
        RenderNodeKind.CODE_BLOCK -> {
            requireLeaf()
            MarkdownRenderBlock.CodeBlock(
                sourceSpan = node.sourceSpan(),
                language = node.title,
                literal = node.text.required("render_code_literal_missing", "code literal"),
            )
        }
        RenderNodeKind.THEMATIC_BREAK -> {
            requireLeaf()
            MarkdownRenderBlock.ThematicBreak(node.sourceSpan())
        }
        RenderNodeKind.TABLE -> toTable()
        RenderNodeKind.HTML_BLOCK -> {
            requireLeaf()
            MarkdownRenderBlock.HtmlBlock(
                sourceSpan = node.sourceSpan(),
                literal = node.text.required("render_html_literal_missing", "HTML literal"),
            )
        }
        else -> failRenderBoundary("render_block_kind_invalid") {
            "${node.kind} cannot appear where a block is required"
        }
    }

private fun RenderTree.toListItem(): MarkdownRenderListItem {
    requireRenderBoundary(node.kind == RenderNodeKind.LIST_ITEM, "render_list_child_invalid") {
        "a list may contain only list-item nodes"
    }
    val actionSpan =
        node.actionStart?.let { start ->
            MarkdownSourceSpan(
                startByte = start,
                endByte = node.actionEnd.required("render_action_span_incomplete", "action end"),
            )
        }
    return MarkdownRenderListItem(
        sourceSpan = node.sourceSpan(),
        actionSpan = actionSpan,
        checked = node.checked,
        blocks = children.map(RenderTree::toBlock),
    )
}

private fun RenderTree.toTable(): MarkdownRenderBlock.Table {
    val header = mutableListOf<MarkdownRenderTableCell>()
    val rowsByIndex = sortedMapOf<UInt, MutableList<MarkdownRenderTableCell>>()
    children.forEach { child ->
        when (child.node.kind) {
            RenderNodeKind.TABLE_HEADER_CELL -> header += child.toTableCell()
            RenderNodeKind.TABLE_CELL -> {
                val rowIndex =
                    child.node.level.required("render_table_row_index_missing", "table row index")
                rowsByIndex.getOrPut(rowIndex) { mutableListOf() } += child.toTableCell()
            }
            else -> failRenderBoundary("render_table_child_invalid") {
                "a table may contain only header-cell and cell nodes"
            }
        }
    }
    rowsByIndex.keys.forEachIndexed { expected, actual ->
        requireRenderBoundary(actual == expected.toUInt(), "render_table_row_index_disconnected") {
            "table row indexes must be contiguous from zero"
        }
    }
    return MarkdownRenderBlock.Table(
        sourceSpan = node.sourceSpan(),
        header = header,
        rows = rowsByIndex.values.map(List<MarkdownRenderTableCell>::toList),
    )
}

private fun RenderTree.toTableCell(): MarkdownRenderTableCell =
    MarkdownRenderTableCell(
        sourceSpan = node.sourceSpan(),
        inlines = children.map(RenderTree::toInline),
    )

private fun RenderTree.toInline(): MarkdownRenderInline =
    when (node.kind) {
        RenderNodeKind.TEXT -> leafText { span, value -> MarkdownRenderInline.Text(span, value) }
        RenderNodeKind.STRONG ->
            MarkdownRenderInline.Strong(node.sourceSpan(), children.map(RenderTree::toInline))
        RenderNodeKind.EMPHASIS ->
            MarkdownRenderInline.Emphasis(node.sourceSpan(), children.map(RenderTree::toInline))
        RenderNodeKind.STRIKETHROUGH ->
            MarkdownRenderInline.Strikethrough(node.sourceSpan(), children.map(RenderTree::toInline))
        RenderNodeKind.HIGHLIGHT ->
            MarkdownRenderInline.Highlight(node.sourceSpan(), children.map(RenderTree::toInline))
        RenderNodeKind.CODE -> leafText { span, value -> MarkdownRenderInline.Code(span, value) }
        RenderNodeKind.LINK ->
            MarkdownRenderInline.Link(
                sourceSpan = node.sourceSpan(),
                destination = node.destination.required("render_link_destination_missing", "link destination"),
                title = node.title,
                inlines = children.map(RenderTree::toInline),
            )
        RenderNodeKind.IMAGE -> {
            requireLeaf()
            MarkdownRenderInline.Image(
                sourceSpan = node.sourceSpan(),
                destination = node.destination.required("render_image_destination_missing", "image destination"),
                title = node.title,
                altText = node.text.required("render_image_alt_missing", "image alt text"),
            )
        }
        RenderNodeKind.TAG ->
            leafText { span, value -> MarkdownRenderInline.Tag(sourceSpan = span, name = value) }
        RenderNodeKind.REMINDER ->
            leafText { span, value -> MarkdownRenderInline.Reminder(sourceSpan = span, token = value) }
        RenderNodeKind.WIKI_REFERENCE ->
            MarkdownRenderInline.WikiReference(
                sourceSpan = node.sourceSpan(),
                target = node.destination.required("render_wiki_target_missing", "wiki target"),
                inlines = children.map(RenderTree::toInline),
            )
        RenderNodeKind.SOFT_BREAK -> {
            requireLeaf()
            MarkdownRenderInline.SoftBreak(node.sourceSpan())
        }
        RenderNodeKind.HARD_BREAK -> {
            requireLeaf()
            MarkdownRenderInline.HardBreak(node.sourceSpan())
        }
        RenderNodeKind.HTML_INLINE ->
            leafText { span, value -> MarkdownRenderInline.HtmlInline(sourceSpan = span, literal = value) }
        else -> failRenderBoundary("render_inline_kind_invalid") {
            "${node.kind} cannot appear where an inline is required"
        }
    }

private inline fun <T : MarkdownRenderInline> RenderTree.leafText(
    factory: (MarkdownSourceSpan, String) -> T,
): T {
    requireLeaf()
    return factory(
        node.sourceSpan(),
        node.text.required("render_inline_text_missing", "inline text"),
    )
}

private fun RenderTree.requireLeaf() {
    requireRenderBoundary(children.isEmpty(), "render_leaf_has_children") {
        "${node.kind} must not contain child nodes"
    }
}

private fun RenderNode.sourceSpan(): MarkdownSourceSpan =
    MarkdownSourceSpan(startByte = sourceStart, endByte = sourceEnd)

private fun <T> T?.required(code: String, field: String): T =
    this ?: failRenderBoundary(code) { "$field is required by render schema v1" }

private fun validateSpan(
    start: ULong,
    end: ULong,
    sourceLength: ULong,
    code: String,
) {
    requireRenderBoundary(start <= end && end <= sourceLength, code) {
        "render span must be ordered and contained by source bytes"
    }
}

private fun validateRenderString(value: String) {
    requireRenderBoundary(
        value.encodeToByteArray().size <= MarkdownRenderDocument.MAX_STRING_UTF8_BYTES,
        "render_string_limit_exceeded",
    ) {
        "render string exceeds ${MarkdownRenderDocument.MAX_STRING_UTF8_BYTES} UTF-8 bytes"
    }
}

private inline fun requireRenderBoundary(
    condition: Boolean,
    code: String,
    message: () -> String,
) {
    if (!condition) throw WorkspaceRenderBoundaryException(code, message())
}

private inline fun failRenderBoundary(
    code: String,
    message: () -> String,
): Nothing = throw WorkspaceRenderBoundaryException(code, message())

internal fun com.lomo.nativebridge.WorkspaceScanPage.toSnapshot(
    exchangeResolver: ExchangeResolver,
): WorkspaceScanPageSnapshot =
    WorkspaceScanPageSnapshot(
        items =
            items.map { item ->
                WorkspaceMemoSummarySnapshot(
                    path = item.path,
                    identity = item.identity,
                    timePart = item.timePart,
                    fingerprint = item.fingerprint,
                    tags = item.tags,
                    attachments = item.attachments,
                    reminders =
                        item.reminders.map { reminder ->
                            WorkspaceReminderReferenceSnapshot(
                                opaqueId = reminder.opaqueId,
                                revision = reminder.revision,
                                memoIdentity = reminder.memoIdentity,
                                sourceStart = reminder.sourceStart,
                                sourceEnd = reminder.sourceEnd,
                                tokenFingerprint = reminder.tokenFingerprint,
                                token = reminder.token,
                                dueAtLocal = reminder.dueAtLocal,
                                repeatCount = reminder.repeatCount,
                                firedCount = reminder.firedCount,
                                done = reminder.done,
                                intervalMinutes = reminder.intervalMinutes,
                                recurrenceCode = reminder.recurrenceCode,
                            )
                        },
                    hasTodo = item.hasTodo,
                    hasUrl = item.hasUrl,
                    content =
                        exchangeResolver.readUtf8Artifact(
                            ExchangeArtifactReference(
                                token = item.content.exchangeToken,
                                length = item.content.length,
                                digest = item.content.digest,
                            ),
                        ),
                    bodyStart = item.bodyStart,
                    bodyEnd = item.bodyEnd,
                    startLine = item.startLine,
                    endLine = item.endLine,
                )
            },
        nextCursor = nextCursor,
    )

internal fun com.lomo.nativebridge.WorkspaceDocumentCommandResult.toSnapshot(): WorkspaceNativeCommandResultSnapshot =
    WorkspaceNativeCommandResultSnapshot(
        path = path,
        resultFingerprint = resultFingerprint,
        bytesWritten = bytesWritten,
    )
