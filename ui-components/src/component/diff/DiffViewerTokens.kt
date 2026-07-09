package com.lomo.ui.component.diff

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.domain.model.SimpleLineDiff
import com.lomo.ui.theme.AppSpacing

internal object DiffViewerTokens {
    val HunkSeparatorPaddingVertical = 2.dp
    val HunkSeparatorPaddingHorizontal = AppSpacing.Small
    val LinePaddingHorizontal = AppSpacing.ExtraSmall
    val LineNumberEndPadding = AppSpacing.ExtraSmall
    val LineNumberFontSize = 11.sp
    val LineContentFontSize = 12.sp

    const val LineNumberWidth = 4

    private const val ChangedLineAlpha = 0.2f
    private const val SecondaryTextAlpha = 0.5f
    private const val EqualContentAlpha = 0.6f

    fun secondaryTextColor(colorScheme: ColorScheme): Color =
        colorScheme.onSurfaceVariant.copy(alpha = SecondaryTextAlpha)

    fun changedLineBackgroundColor(
        colorScheme: ColorScheme,
        op: SimpleLineDiff.DiffOp,
    ): Color =
        when (op) {
            SimpleLineDiff.DiffOp.DELETE -> colorScheme.errorContainer.copy(alpha = ChangedLineAlpha)
            SimpleLineDiff.DiffOp.INSERT -> colorScheme.primaryContainer.copy(alpha = ChangedLineAlpha)
            SimpleLineDiff.DiffOp.EQUAL -> Color.Transparent
        }

    fun lineContentColor(
        colorScheme: ColorScheme,
        op: SimpleLineDiff.DiffOp,
    ): Color =
        when (op) {
            SimpleLineDiff.DiffOp.EQUAL -> colorScheme.onSurface.copy(alpha = EqualContentAlpha)
            else -> colorScheme.onSurface
        }
}
