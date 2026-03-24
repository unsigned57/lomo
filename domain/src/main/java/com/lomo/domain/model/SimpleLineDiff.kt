package com.lomo.domain.model

/**
 * Lightweight line-based diff using a simplified Myers algorithm.
 * Produces grouped hunks with context lines for display.
 */
object SimpleLineDiff {
    enum class DiffOp { EQUAL, INSERT, DELETE }

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

    fun diff(
        oldText: String,
        newText: String,
    ): List<DiffHunk> {
        if (oldText == newText) return emptyList()

        val oldLines = oldText.lines()
        val newLines = newText.lines()
        val rawDiff = computeLcs(oldLines, newLines)
        return groupIntoHunks(rawDiff)
    }

    private fun computeLcs(
        oldLines: List<String>,
        newLines: List<String>,
    ): List<DiffLine> {
        val n = oldLines.size
        val m = newLines.size

        // Build LCS table
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] =
                    if (oldLines[i] == newLines[j]) {
                        dp[i + 1][j + 1] + 1
                    } else {
                        maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
            }
        }

        // Trace back to produce diff lines
        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        var oldLineNo = 1
        var newLineNo = 1

        while (i < n || j < m) {
            when {
                i < n && j < m && oldLines[i] == newLines[j] -> {
                    result.add(DiffLine(DiffOp.EQUAL, oldLines[i], oldLineNo++, newLineNo++))
                    i++
                    j++
                }
                j < m && (i >= n || dp[i][j + 1] >= dp[i + 1][j]) -> {
                    result.add(DiffLine(DiffOp.INSERT, newLines[j], null, newLineNo++))
                    j++
                }
                else -> {
                    result.add(DiffLine(DiffOp.DELETE, oldLines[i], oldLineNo++, null))
                    i++
                }
            }
        }

        return result
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
