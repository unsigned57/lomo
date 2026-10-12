package com.lomo.ui.component.markdown

import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import kotlinx.collections.immutable.toImmutableList

/**
 * Compose-facing layout plan built exclusively from the Rust-owned typed Render IR.
 *
 * This policy may group media, bound visible blocks, and apply presentation policies (such as
 * filtering inline tags/reminders and pruning empty blocks), but it receives no Markdown source and has
 * no parser or semantic fallback surface.
 */
data class MarkdownIrPresentationPlan(
    val totalBlocks: Int,
    val items: List<MarkdownIrPresentationItem>,
)

sealed interface MarkdownIrPresentationItem {
    /**
     * [block] keeps the typed IR for semantic consumers (summaries, interaction facts) while
     * [display] carries every derived render value precomputed at plan time.
     */
    data class Block(
        val block: MarkdownRenderBlock,
        val display: MarkdownIrBlockDisplay,
    ) : MarkdownIrPresentationItem

    data class Gallery(
        val images: kotlinx.collections.immutable.ImmutableList<MarkdownPresentationImage>,
    ) : MarkdownIrPresentationItem
}

fun buildMarkdownIrPresentationPlan(
    document: MarkdownRenderDocument,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    policy: MarkdownPresentationPolicy = MarkdownPresentationPolicy.DEFAULT,
): MarkdownIrPresentationPlan {
    require(maxVisibleBlocks >= 0) { "maxVisibleBlocks must not be negative" }
    val filteredBlocks =
        document.blocks.mapNotNull { block ->
            MarkdownPresentationFilter.filterBlock(block, policy)
        }
    return MarkdownIrPresentationPlan(
        totalBlocks = filteredBlocks.size,
        items = buildPresentationItems(filteredBlocks.take(maxVisibleBlocks)),
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
                items += presentationBlockItem(pendingImageBlocks.single())
            else ->
                items +=
                    MarkdownIrPresentationItem.Gallery(
                        images =
                            pendingImages
                                .map(MarkdownRenderInline.Image::toPresentationImage)
                                .toImmutableList(),
                    )
        }
        pendingImageBlocks.clear()
        pendingImages.clear()
    }

    blocks.forEach { block ->
        val images = block.imageOnlyParagraphImages()
        if (images == null) {
            flushImages()
            items += presentationBlockItem(block)
        } else {
            pendingImageBlocks += block as MarkdownRenderBlock.Paragraph
            pendingImages += images
        }
    }
    flushImages()
    return items
}

private fun presentationBlockItem(
    block: MarkdownRenderBlock,
): MarkdownIrPresentationItem.Block =
    MarkdownIrPresentationItem.Block(
        block = block,
        display = buildMarkdownIrBlockDisplay(block),
    )

private fun MarkdownRenderBlock.imageOnlyParagraphImages(): List<MarkdownRenderInline.Image>? {
    val paragraph = this as? MarkdownRenderBlock.Paragraph ?: return null
    if (paragraph.inlines.isEmpty()) return null
    if (paragraph.inlines.any { inline -> inline !is MarkdownRenderInline.Image }) return null
    val images = paragraph.inlines.map { inline -> inline as MarkdownRenderInline.Image }
    // Audio embeds stay out of the image pager and render per block through the media lane;
    // every other destination joins the gallery and fails closed inside image loading.
    if (images.any { image -> image.mediaClass == MediaClass.AUDIO }) return null
    return images
}
