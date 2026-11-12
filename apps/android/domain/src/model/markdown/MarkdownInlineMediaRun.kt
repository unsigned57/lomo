package com.lomo.domain.model.markdown

import com.lomo.domain.model.MediaClass

/** Ordered presentation runs over typed IR; this does not parse Markdown. */
sealed interface MarkdownInlineMediaRun {
    data class Text(val inlines: List<MarkdownRenderInline>) : MarkdownInlineMediaRun
    data class Media(val image: MarkdownRenderInline.Image) : MarkdownInlineMediaRun
}

/** Formatting survives extraction; only layout breaks adjacent to extracted media are removed. */
fun splitMarkdownInlineMedia(
    inlines: List<MarkdownRenderInline>,
    extract: Set<MediaClass> = MediaClass.entries.toSet(),
): List<MarkdownInlineMediaRun> {
    val runs = mutableListOf<MarkdownInlineMediaRun>()
    val pending = mutableListOf<MarkdownRenderInline>()
    fun flush() {
        val text = pending.toList().trimMediaBoundaryBreaks(trimStart = runs.isNotEmpty())
        if (text.isNotEmpty()) runs += MarkdownInlineMediaRun.Text(text)
        pending.clear()
    }
    fun visit(inline: MarkdownRenderInline, wrap: (MarkdownRenderInline) -> MarkdownRenderInline) {
        fun children(items: List<MarkdownRenderInline>, wrapChild: (MarkdownRenderInline) -> MarkdownRenderInline) {
            if (items.isEmpty()) {
                pending += wrap(inline)
            } else {
                items.forEach { child -> visit(child) { wrap(wrapChild(it)) } }
            }
        }
        when (inline) {
            is MarkdownRenderInline.Image -> if (inline.mediaClass in extract) {
                flush()
                runs += MarkdownInlineMediaRun.Media(inline)
            } else {
                pending += wrap(inline)
            }

            is MarkdownRenderInline.Strong -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Emphasis -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Strikethrough -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Underline -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Highlight -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.Link -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            is MarkdownRenderInline.WikiReference -> children(inline.inlines) { inline.copy(inlines = listOf(it)) }

            else -> pending += wrap(inline)
        }
    }
    inlines.forEach { visit(it) { leaf -> leaf } }
    if (runs.isEmpty()) return listOf(MarkdownInlineMediaRun.Text(inlines))
    flush()
    return runs
}

private fun List<MarkdownRenderInline>.trimMediaBoundaryBreaks(trimStart: Boolean): List<MarkdownRenderInline> {
    val withoutTail = dropLastWhile(MarkdownRenderInline::isMediaBoundaryWhitespace)
    return if (trimStart) withoutTail.dropWhile(MarkdownRenderInline::isMediaBoundaryWhitespace) else withoutTail
}

private fun MarkdownRenderInline.isMediaBoundaryWhitespace(): Boolean = when (this) {
    is MarkdownRenderInline.SoftBreak, is MarkdownRenderInline.HardBreak -> true
    is MarkdownRenderInline.Text -> text.isBlank()
    is MarkdownRenderInline.Strong -> inlines.all { it.isMediaBoundaryWhitespace() }
    is MarkdownRenderInline.Emphasis -> inlines.all { it.isMediaBoundaryWhitespace() }
    is MarkdownRenderInline.Strikethrough -> inlines.all { it.isMediaBoundaryWhitespace() }
    is MarkdownRenderInline.Underline -> inlines.all { it.isMediaBoundaryWhitespace() }
    is MarkdownRenderInline.Highlight -> inlines.all { it.isMediaBoundaryWhitespace() }
    is MarkdownRenderInline.Link -> inlines.isNotEmpty() && inlines.all { it.isMediaBoundaryWhitespace() }
    is MarkdownRenderInline.WikiReference -> inlines.isNotEmpty() && inlines.all { it.isMediaBoundaryWhitespace() }
    else -> false
}
