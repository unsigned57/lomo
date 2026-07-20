package com.lomo.domain.model.markdown

import com.lomo.domain.model.MediaFileExtensions
import com.lomo.domain.model.MemoContentAnalysis

/** Projects query/storage facts from the validated Rust-owned render document. */
fun MarkdownRenderDocument.toMemoContentAnalysis(): MemoContentAnalysis {
    val audioUrls = attachmentDestinations.filter(MediaFileExtensions::hasAudioExtension)
    val imageUrls = attachmentDestinations.filterNot(MediaFileExtensions::hasAudioExtension)
    return MemoContentAnalysis(
        hasTodo = blocks.any(MarkdownRenderBlock::containsTask),
        hasAttachment = attachmentDestinations.isNotEmpty(),
        hasUrl = blocks.any(MarkdownRenderBlock::containsExternalLink),
        tags = tagNames,
        imageUrls = imageUrls,
        audioUrls = audioUrls,
    )
}

private fun MarkdownRenderBlock.containsTask(): Boolean =
    when (this) {
        is MarkdownRenderBlock.BlockQuote -> blocks.any(MarkdownRenderBlock::containsTask)
        is MarkdownRenderBlock.ListBlock ->
            items.any { item -> item.checked != null || item.blocks.any(MarkdownRenderBlock::containsTask) }
        else -> false
    }

private fun MarkdownRenderBlock.containsExternalLink(): Boolean =
    when (this) {
        is MarkdownRenderBlock.Paragraph -> inlines.any(MarkdownRenderInline::containsExternalLink)
        is MarkdownRenderBlock.Heading -> inlines.any(MarkdownRenderInline::containsExternalLink)
        is MarkdownRenderBlock.BlockQuote -> blocks.any(MarkdownRenderBlock::containsExternalLink)
        is MarkdownRenderBlock.ListBlock ->
            items.any { item -> item.blocks.any(MarkdownRenderBlock::containsExternalLink) }
        is MarkdownRenderBlock.Table ->
            (header.asSequence() + rows.asSequence().flatten())
                .any { cell -> cell.inlines.any(MarkdownRenderInline::containsExternalLink) }
        else -> false
    }

private fun MarkdownRenderInline.containsExternalLink(): Boolean =
    when (this) {
        is MarkdownRenderInline.Link ->
            destination.startsWith("http://", ignoreCase = true) ||
                destination.startsWith("https://", ignoreCase = true) ||
                destination.startsWith("mailto:", ignoreCase = true) ||
                destination.startsWith("geo:", ignoreCase = true) ||
                inlines.any(MarkdownRenderInline::containsExternalLink)
        is MarkdownRenderInline.Strong -> inlines.any(MarkdownRenderInline::containsExternalLink)
        is MarkdownRenderInline.Emphasis -> inlines.any(MarkdownRenderInline::containsExternalLink)
        is MarkdownRenderInline.Strikethrough -> inlines.any(MarkdownRenderInline::containsExternalLink)
        is MarkdownRenderInline.Highlight -> inlines.any(MarkdownRenderInline::containsExternalLink)
        is MarkdownRenderInline.WikiReference -> inlines.any(MarkdownRenderInline::containsExternalLink)
        else -> false
    }
