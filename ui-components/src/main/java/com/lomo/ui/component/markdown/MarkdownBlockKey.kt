package com.lomo.ui.component.markdown

/**
 * Stable, equality-comparable keys for selectable markdown leaves rendered by
 * [ModernMarkdownAstRenderer]. The cross-paragraph [MemoTextSelectionScope]
 * uses these keys to identify which paragraph a long-press / drag targets;
 * deriving them from the original AST offset plus a kind discriminator keeps
 * the key stable across recompositions so the registrar can hold a single
 * entry per block even as the markdown plan rebuilds in-place.
 */
internal sealed class MarkdownBlockKey {
    private data class Heading(val startOffset: Int) : MarkdownBlockKey()
    private data class ParagraphItem(val startOffset: Int, val itemIndex: Int) : MarkdownBlockKey()
    private data class CodeFence(val startOffset: Int) : MarkdownBlockKey()
    private data class CodeBlock(val startOffset: Int) : MarkdownBlockKey()
    private data class TableCell(val startOffset: Int, val rowIndex: Int, val cellIndex: Int) : MarkdownBlockKey()
    private data class Fallback(val startOffset: Int) : MarkdownBlockKey()

    companion object {
        fun heading(startOffset: Int): Any = Heading(startOffset)
        fun paragraphItem(startOffset: Int, itemIndex: Int): Any = ParagraphItem(startOffset, itemIndex)
        fun codeFence(startOffset: Int): Any = CodeFence(startOffset)
        fun codeBlock(startOffset: Int): Any = CodeBlock(startOffset)
        fun tableCell(startOffset: Int, rowIndex: Int, cellIndex: Int): Any =
            TableCell(startOffset, rowIndex, cellIndex)
        fun fallback(startOffset: Int): Any = Fallback(startOffset)
    }
}
