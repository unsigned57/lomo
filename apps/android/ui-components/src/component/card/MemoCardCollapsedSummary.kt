package com.lomo.ui.component.card

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.lomo.ui.text.MemoParagraphText
import com.lomo.ui.text.MemoTextSelectionRegistrar
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.theme.memoSummaryTextStyle

@Composable
internal fun MemoCardCollapsedSummary(
    collapsedSummary: String,
    allowFreeTextCopy: Boolean,
    onBodyClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    selectionRegistrar: MemoTextSelectionRegistrar? = null,
) {
    val displaySummary = collapsedSummary.normalizeCjkMixedSpacingForDisplay()
    val summaryStyle =
        MaterialTheme.typography.memoSummaryTextStyle()
            .copy(color = MaterialTheme.colorScheme.onSurface)
            .scriptAwareFor(displaySummary)
    MemoParagraphText(
        text = displaySummary,
        style = summaryStyle,
        maxLines = COLLAPSED_SUMMARY_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = MemoCardTokens.BodyVerticalPadding),
        selectable = allowFreeTextCopy,
        selectionRegistrar = selectionRegistrar,
        onBodyClick = onBodyClick,
        onLongClick = onLongClick,
    )
}
