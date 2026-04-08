package com.lomo.ui.component.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.domain.model.SimpleLineDiff
import kotlinx.collections.immutable.ImmutableList

private const val DIFF_CHANGED_LINE_ALPHA = 0.2f
private const val DIFF_LINE_NUMBER_WIDTH = 4
private const val DIFF_LINE_NUMBER_FONT_SIZE = 11
private const val DIFF_LINE_CONTENT_FONT_SIZE = 12

@Composable
fun DiffViewer(
    hunks: ImmutableList<SimpleLineDiff.DiffHunk>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        hunks.forEachIndexed { hunkIndex, hunk ->
            if (hunkIndex > 0) {
                Text(
                    text = "\u00b7\u00b7\u00b7",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 2.dp, horizontal = 8.dp),
                )
            }
            hunk.lines.forEach { line ->
                DiffLineRow(line)
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: SimpleLineDiff.DiffLine) {
    val bgColor = when (line.op) {
        SimpleLineDiff.DiffOp.DELETE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = DIFF_CHANGED_LINE_ALPHA)
        SimpleLineDiff.DiffOp.INSERT -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = DIFF_CHANGED_LINE_ALPHA)
        SimpleLineDiff.DiffOp.EQUAL -> Color.Transparent
    }
    val prefix = when (line.op) {
        SimpleLineDiff.DiffOp.DELETE -> "-"
        SimpleLineDiff.DiffOp.INSERT -> "+"
        SimpleLineDiff.DiffOp.EQUAL -> " "
    }
    val textColor = when (line.op) {
        SimpleLineDiff.DiffOp.EQUAL -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val lineNoText = buildString {
        append((line.oldLineNumber?.toString() ?: "").padStart(DIFF_LINE_NUMBER_WIDTH))
        append(" ")
        append((line.newLineNumber?.toString() ?: "").padStart(DIFF_LINE_NUMBER_WIDTH))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text = lineNoText,
            fontFamily = FontFamily.Monospace,
            fontSize = DIFF_LINE_NUMBER_FONT_SIZE.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text = "$prefix ${line.text}",
            fontFamily = FontFamily.Monospace,
            fontSize = DIFF_LINE_CONTENT_FONT_SIZE.sp,
            color = textColor,
            maxLines = 1,
        )
    }
}
