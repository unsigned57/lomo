package com.lomo.ui.component.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.lomo.domain.model.SimpleLineDiff
import kotlinx.collections.immutable.ImmutableList

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
                    color = DiffViewerTokens.secondaryTextColor(MaterialTheme.colorScheme),
                    modifier =
                        Modifier.padding(
                            vertical = DiffViewerTokens.HunkSeparatorPaddingVertical,
                            horizontal = DiffViewerTokens.HunkSeparatorPaddingHorizontal,
                        ),
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
    val bgColor = DiffViewerTokens.changedLineBackgroundColor(MaterialTheme.colorScheme, line.op)
    val prefix = when (line.op) {
        SimpleLineDiff.DiffOp.DELETE -> "-"
        SimpleLineDiff.DiffOp.INSERT -> "+"
        SimpleLineDiff.DiffOp.EQUAL -> " "
    }
    val textColor = DiffViewerTokens.lineContentColor(MaterialTheme.colorScheme, line.op)
    val lineNoText = buildString {
        append((line.oldLineNumber?.toString() ?: "").padStart(DiffViewerTokens.LineNumberWidth))
        append(" ")
        append((line.newLineNumber?.toString() ?: "").padStart(DiffViewerTokens.LineNumberWidth))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = DiffViewerTokens.LinePaddingHorizontal),
    ) {
        Text(
            text = lineNoText,
            fontFamily = FontFamily.Monospace,
            fontSize = DiffViewerTokens.LineNumberFontSize,
            color = DiffViewerTokens.secondaryTextColor(MaterialTheme.colorScheme),
            modifier = Modifier.padding(end = DiffViewerTokens.LineNumberEndPadding),
        )
        Text(
            text = "$prefix ${line.text}",
            fontFamily = FontFamily.Monospace,
            fontSize = DiffViewerTokens.LineContentFontSize,
            color = textColor,
            maxLines = 1,
        )
    }
}
