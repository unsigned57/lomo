package com.lomo.ui.component.markdown

import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline

/**
 * Compose-facing layout plan built exclusively from the Rust-owned typed Render IR.
 *
 * This policy may group media and bound visible blocks, but it receives no Markdown source and has
 * no parser or semantic fallback surface.
 */
data class MarkdownIrPresentationPlan(
    val totalBlocks: Int,
    val items: List<MarkdownIrPresentationItem>,
)

sealed interface MarkdownIrPresentationItem {
    data class Block(
        val block: MarkdownRenderBlock,
    ) : MarkdownIrPresentationItem

    data class Gallery(
        val images: List<MarkdownRenderInline.Image>,
    ) : MarkdownIrPresentationItem
}

fun buildMarkdownIrPresentationPlan(
    document: MarkdownRenderDocument,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
): MarkdownIrPresentationPlan {
    require(maxVisibleBlocks >= 0) { "maxVisibleBlocks must not be negative" }
    return MarkdownIrPresentationPlan(
        totalBlocks = document.blocks.size,
        items = buildPresentationItems(document.blocks.take(maxVisibleBlocks)),
    )
}

private fun buildPresentationItems(
    blocks: List<MarkdownRenderBlock>,
): List<MarkdownIrPresentationItem> {
    val items = mutableListOf<MarkdownIrPresentationItem>()
    val pendingImageBlocks = mutableListOf<MarkdownRenderBlock.Paragraph>()
    val pendingImages = mutableListOf<MarkdownRenderInline.Image>()

    fun flushImages() {
        when {
            pendingImages.isEmpty() -> Unit
            pendingImages.size == 1 ->
                items += MarkdownIrPresentationItem.Block(pendingImageBlocks.single())
            else -> items += MarkdownIrPresentationItem.Gallery(pendingImages.toList())
        }
        pendingImageBlocks.clear()
        pendingImages.clear()
    }

    blocks.forEach { block ->
        val images = block.imageOnlyParagraphImages()
        if (images == null) {
            flushImages()
            items += MarkdownIrPresentationItem.Block(block)
        } else {
            pendingImageBlocks += block as MarkdownRenderBlock.Paragraph
            pendingImages += images
        }
    }
    flushImages()
    return items
}

private fun MarkdownRenderBlock.imageOnlyParagraphImages(): List<MarkdownRenderInline.Image>? {
    val paragraph = this as? MarkdownRenderBlock.Paragraph ?: return null
    if (paragraph.inlines.isEmpty()) return null
    if (paragraph.inlines.any { inline -> inline !is MarkdownRenderInline.Image }) return null
    return paragraph.inlines.map { inline -> inline as MarkdownRenderInline.Image }
}
