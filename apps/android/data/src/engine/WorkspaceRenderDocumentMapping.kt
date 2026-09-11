package com.lomo.data.engine

import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.nativebridge.RenderDocument
import com.lomo.nativebridge.RenderNode

/**
 Transport-level render document conversion and node validation.
 */

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

internal fun RenderNode.validateTransportNode(sourceLength: ULong) {
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
