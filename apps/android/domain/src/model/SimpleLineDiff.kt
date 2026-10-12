package com.lomo.domain.model

import com.github.difflib.DiffUtils

/**
 * Lightweight line-based diff backed by java-diff-utils Myers.
 * Produces grouped hunks with context lines for display.
 */
object SimpleLineDiff {
    enum class DiffOp { EQUAL, INSERT, DELETE }

    sealed interface DiffResult {
        data class Computed(
            val hunks: List<DiffHunk>,
        ) : DiffResult

        data class TooLarge(
            val oldLineCount: Int,
            val newLineCount: Int,
            val maxComparedCells: Int,
            val maxDiffLineCount: Int = DEFAULT_MAX_DIFF_LINE_COUNT,
        ) : DiffResult
    }

    data class DiffLine(
        val op: DiffOp,
        val text: String,
        val oldLineNumber: Int? = null,
        val newLineNumber: Int? = null,
    )

    data class DiffHunk(
        val lines: List<DiffLine>,
    )

    private const val CONTEXT_LINES = 3
    private const val DEFAULT_MAX_COMPARED_CELLS = 250_000
    private const val DEFAULT_MAX_DIFF_LINE_COUNT = 2_000

    fun diff(
        oldText: String,
        newText: String,
    ): List<DiffHunk> =
        when (val result = diffResult(oldText = oldText, newText = newText)) {
            is DiffResult.Computed -> result.hunks
            is DiffResult.TooLarge -> emptyList()
        }

    fun diffResult(
        oldText: String,
        newText: String,
        maxComparedCells: Int = DEFAULT_MAX_COMPARED_CELLS,
        maxDiffLineCount: Int = DEFAULT_MAX_DIFF_LINE_COUNT,
    ): DiffResult {
        if (oldText == newText) return DiffResult.Computed(emptyList())
        val oldLines = oldText.lines()
        val newLines = newText.lines()
        if (
            exceedsBudget(
                oldLineCount = oldLines.size,
                newLineCount = newLines.size,
                maxComparedCells = maxComparedCells,
                maxDiffLineCount = maxDiffLineCount,
            )
        ) {
            return DiffResult.TooLarge(
                oldLineCount = oldLines.size,
                newLineCount = newLines.size,
                maxComparedCells = maxComparedCells,
                maxDiffLineCount = maxDiffLineCount,
            )
        }

        val rawDiff = computeDiffLines(oldLines, newLines)
        return DiffResult.Computed(groupIntoHunks(rawDiff))
    }

    private fun exceedsBudget(
        oldLineCount: Int,
        newLineCount: Int,
        maxComparedCells: Int,
        maxDiffLineCount: Int,
    ): Boolean =
        exceedsComparedCellBudget(
            oldLineCount = oldLineCount,
            newLineCount = newLineCount,
            maxComparedCells = maxComparedCells,
        ) || exceedsDiffLineBudget(
            oldLineCount = oldLineCount,
            newLineCount = newLineCount,
            maxDiffLineCount = maxDiffLineCount,
        )

    private fun exceedsComparedCellBudget(
        oldLineCount: Int,
        newLineCount: Int,
        maxComparedCells: Int,
    ): Boolean =
        comparedCellCount(oldLineCount = oldLineCount, newLineCount = newLineCount) >
            maxComparedCells.toLong()

    private fun comparedCellCount(
        oldLineCount: Int,
        newLineCount: Int,
    ): Long =
        (oldLineCount.toLong() + 1L) * (newLineCount.toLong() + 1L)

    private fun exceedsDiffLineBudget(
        oldLineCount: Int,
        newLineCount: Int,
        maxDiffLineCount: Int,
    ): Boolean =
        oldLineCount.toLong() + newLineCount.toLong() > maxDiffLineCount.toLong()

    /**
     * Adapts java-diff-utils Myers deltas into `DiffLine`s: deltas arrive in
     * document order, so leading equal runs precede each chunk and every source
     * chunk deletes before its target chunk inserts.
     */
    private fun computeDiffLines(
        oldLines: List<String>,
        newLines: List<String>,
    ): List<DiffLine> {
        val lines = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0
        for (delta in DiffUtils.diff(oldLines, newLines).deltas) {
            val source = delta.source
            val target = delta.target
            while (oldIndex < source.position) {
                lines.add(DiffLine(DiffOp.EQUAL, oldLines[oldIndex], oldIndex + 1, newIndex + 1))
                oldIndex += 1
                newIndex += 1
            }
            for (line in source.lines) {
                lines.add(DiffLine(DiffOp.DELETE, line, oldIndex + 1, null))
                oldIndex += 1
            }
            for (line in target.lines) {
                lines.add(DiffLine(DiffOp.INSERT, line, null, newIndex + 1))
                newIndex += 1
            }
        }
        while (oldIndex < oldLines.size) {
            lines.add(DiffLine(DiffOp.EQUAL, oldLines[oldIndex], oldIndex + 1, newIndex + 1))
            oldIndex += 1
            newIndex += 1
        }
        return lines
    }

    private fun groupIntoHunks(diffLines: List<DiffLine>): List<DiffHunk> {
        val changeIndices = diffLines.indices.filter { diffLines[it].op != DiffOp.EQUAL }
        if (diffLines.isEmpty() || changeIndices.isEmpty()) return emptyList()

        // Build spans: each change expands to include context
        class Span(
            val start: Int,
            val end: Int,
        )

        val spans = mutableListOf<Span>()
        for (idx in changeIndices) {
            val start = maxOf(0, idx - CONTEXT_LINES)
            val end = minOf(diffLines.size - 1, idx + CONTEXT_LINES)
            if (spans.isNotEmpty() && start <= spans.last().end + 1) {
                val previousSpan = spans.last()
                spans[spans.lastIndex] =
                    Span(
                        start = previousSpan.start,
                        end = end,
                    )
            } else {
                spans.add(Span(start, end))
            }
        }

        return spans.map { span ->
            DiffHunk(lines = diffLines.subList(span.start, span.end + 1))
        }
    }
}
