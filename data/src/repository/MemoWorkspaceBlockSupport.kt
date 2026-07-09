package com.lomo.data.repository

import android.net.Uri
import com.lomo.data.parser.MarkdownMemoBlock
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.parser.MarkdownSourceSpan
import com.lomo.data.util.IndexedTextLines
import com.lomo.domain.model.Memo

internal fun replaceMemoBlockContent(
    currentFileContent: String,
    dateKey: String,
    memo: Memo,
    replacementLines: List<String>,
    parser: MarkdownParser,
): String? {
    val lines = IndexedTextLines.of(currentFileContent)
    val span = findParsedMemoBlock(currentFileContent, dateKey, memo, parser)?.span ?: return null
    if (!span.isValidFor(lines)) {
        return null
    }
    return rebuildMemoContent(
        lines = lines,
        startIndex = span.startLine,
        endIndex = span.endLine,
        replacementLines = replacementLines,
    )
}

internal fun removeMemoBlockFromContent(
    content: String,
    dateKey: String,
    memo: Memo,
    parser: MarkdownParser,
): RemovedMemoBlock? {
    val lines = IndexedTextLines.of(content)
    val block = findParsedMemoBlock(content, dateKey, memo, parser) ?: return null
    val span = block.span
    return if (span.isValidFor(lines)) {
        RemovedMemoBlock(
            remainingContent = rebuildRemainingMemoContent(lines, span.startLine, span.endLine),
            blockContent = lines.toBlockContent(span),
        )
    } else {
        null
    }
}

internal fun containsMemoBlock(
    content: String,
    dateKey: String,
    memo: Memo,
    parser: MarkdownParser,
): Boolean = findParsedMemoBlock(content, dateKey, memo, parser)?.span?.isValidFor(IndexedTextLines.of(content)) == true

internal fun Memo.filename(): String = "$dateKey.md"

internal fun Memo.toBlockContent(): String =
    buildString {
        appendLine()
        append(rawContent)
        appendLine()
    }

internal fun String?.toPersistedUriOrNull(): Uri? =
    this
        ?.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        ?.let(Uri::parse)

private fun rebuildRemainingMemoContent(
    lines: List<String>,
    startIndex: Int,
    endIndex: Int,
): String =
    buildString(lines.sumOf(String::length) + lines.size) {
        for (index in lines.indices) {
            if (index in startIndex..endIndex) {
                continue
            }
            if (isNotEmpty()) {
                append('\n')
            }
            append(lines[index])
        }
    }

private fun findParsedMemoBlock(
    content: String,
    dateKey: String,
    memo: Memo,
    parser: MarkdownParser,
): MarkdownMemoBlock? =
    parser
        .parseDocument(content = content, filename = dateKey)
        .blocks
        .singleOrNull { block -> block.memo.id == memo.id }

private fun MarkdownSourceSpan.isValidFor(lines: List<String>): Boolean =
    startLine >= 0 && endLine >= startLine && endLine < lines.size

private fun List<String>.toBlockContent(span: MarkdownSourceSpan): String =
    buildString {
        appendLine()
        for (index in span.startLine..span.endLine) {
            if (index > span.startLine) {
                append('\n')
            }
            append(this@toBlockContent[index])
        }
        appendLine()
    }
