package com.lomo.app.feature.main

import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderInline

internal fun MarkdownRenderBlock.resolveImages(resolve: (String) -> String): MarkdownRenderBlock =
    when (this) {
        is MarkdownRenderBlock.Paragraph -> copy(inlines = inlines.resolveImages(resolve))
        is MarkdownRenderBlock.Heading -> copy(inlines = inlines.resolveImages(resolve))
        is MarkdownRenderBlock.BlockQuote ->
            copy(blocks = blocks.map { it.resolveImages(resolve) })
        is MarkdownRenderBlock.ListBlock ->
            copy(
                items =
                    items.map { item ->
                        item.copy(blocks = item.blocks.map { it.resolveImages(resolve) })
                    },
            )
        is MarkdownRenderBlock.Table ->
            copy(
                header =
                    header.map { cell ->
                        cell.copy(inlines = cell.inlines.resolveImages(resolve))
                    },
                rows =
                    rows.map { row ->
                        row.map { cell ->
                            cell.copy(inlines = cell.inlines.resolveImages(resolve))
                        }
                    },
            )
        is MarkdownRenderBlock.CodeBlock,
        is MarkdownRenderBlock.ThematicBreak,
        is MarkdownRenderBlock.HtmlBlock,
        -> this
    }

internal fun List<MarkdownRenderInline>.resolveImages(resolve: (String) -> String): List<MarkdownRenderInline> =
    map { inline ->
        when (inline) {
            is MarkdownRenderInline.Strong ->
                inline.copy(inlines = inline.inlines.resolveImages(resolve))
            is MarkdownRenderInline.Emphasis ->
                inline.copy(inlines = inline.inlines.resolveImages(resolve))
            is MarkdownRenderInline.Strikethrough ->
                inline.copy(inlines = inline.inlines.resolveImages(resolve))
            is MarkdownRenderInline.Highlight ->
                inline.copy(inlines = inline.inlines.resolveImages(resolve))
            is MarkdownRenderInline.Link ->
                inline.copy(inlines = inline.inlines.resolveImages(resolve))
            is MarkdownRenderInline.Image ->
                inline.copy(destination = resolve(inline.destination))
            is MarkdownRenderInline.WikiReference ->
                inline.copy(inlines = inline.inlines.resolveImages(resolve))
            else -> inline
        }
    }
