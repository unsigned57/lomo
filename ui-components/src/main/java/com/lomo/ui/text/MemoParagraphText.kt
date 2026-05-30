package com.lomo.ui.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.lomo.ui.theme.memoPlatformTextHandleColor
import com.lomo.ui.theme.memoPlatformTextSelectionHighlightColor

private const val SEARCH_HIGHLIGHT_ALPHA = 153
private const val COLOR_COMPONENT_MAX = 255f

@Composable
internal fun MemoParagraphText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    selectable: Boolean = false,
    blockKey: Any? = null,
    selectionRegistrar: MemoTextSelectionRegistrar? = null,
    onTapFeedback: (() -> Unit)? = null,
    onBodyClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    MemoParagraphText(
        text = AnnotatedString(text),
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        selectable = selectable,
        blockKey = blockKey,
        selectionRegistrar = selectionRegistrar,
        onTapFeedback = onTapFeedback,
        onBodyClick = onBodyClick,
        onDoubleClick = onDoubleClick,
        onLongClick = onLongClick,
    )
}

@Composable
internal fun MemoParagraphText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    selectable: Boolean = false,
    blockKey: Any? = null,
    selectionRegistrar: MemoTextSelectionRegistrar? = null,
    onTapFeedback: (() -> Unit)? = null,
    onBodyClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val fallbackColor = colorScheme.onSurface
    val resolvedStyle =
        remember(style, fallbackColor) {
            if (style.color == Color.Unspecified) style.copy(color = fallbackColor) else style
        }
    val searchQuery = LocalSearchHighlightQuery.current
    val searchHighlightColor = colorScheme.tertiary.copy(alpha = SEARCH_HIGHLIGHT_ALPHA / COLOR_COMPONENT_MAX)
    val highlightedText =
        remember(text, searchQuery, searchHighlightColor) {
            text.withSearchHighlight(query = searchQuery, highlightColor = searchHighlightColor)
        }
    val hasSearchHighlights = highlightedText.spanStyles.size > text.spanStyles.size
    val renderingDecision =
        remember(
            highlightedText,
            text.spanStyles,
            text.paragraphStyles,
            selectable,
            onTapFeedback,
            onBodyClick,
            onDoubleClick,
            onLongClick,
            hasSearchHighlights,
        ) {
            resolveMemoTextRenderingPolicy(
                MemoTextRenderingRequest(
                    text = highlightedText.text,
                    selectionRequired = selectable,
                    tapHandlingRequired = onTapFeedback != null ||
                        onBodyClick != null ||
                        onDoubleClick != null ||
                        onLongClick != null,
                    hasLinks = highlightedText.toMemoTextLinkRanges().isNotEmpty(),
                    hasInlineStyles = text.spanStyles.isNotEmpty() || text.paragraphStyles.isNotEmpty(),
                    hasSearchHighlights = hasSearchHighlights,
                ),
            )
        }

    when (renderingDecision.renderer) {
        MemoTextRenderer.PlatformText ->
            Text(
                text = highlightedText.text,
                style = resolvedStyle,
                modifier = modifier,
                maxLines = maxLines,
                overflow = overflow,
            )

        MemoTextRenderer.CustomCanvasText ->
            MemoComposeParagraphText(
                text = highlightedText,
                style = resolvedStyle,
                modifier = modifier,
                maxLines = maxLines,
                overflow = overflow,
                selectable = selectable,
                selectionHighlightColor = memoPlatformTextSelectionHighlightColor(colorScheme),
                defaultLinkColor = memoPlatformTextHandleColor(colorScheme),
                blockKey = blockKey,
                selectionRegistrar = selectionRegistrar,
                onTapFeedback = onTapFeedback,
                onBodyClick = onBodyClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick,
            )
    }
}
