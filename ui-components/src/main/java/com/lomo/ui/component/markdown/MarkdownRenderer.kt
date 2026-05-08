package com.lomo.ui.component.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.lomo.ui.text.MemoParagraphText
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay
import com.lomo.ui.text.resolveRawMemoPlainTextStyle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf

@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: ImmutableMap<Int, Boolean> = persistentHashMapOf(),
    onImageClick: ((String) -> Unit)? = null,
    onTotalBlocks: ((Int) -> Unit)? = null,
    precomputedRenderPlan: ModernMarkdownRenderPlan? = null,
    knownTagsToStrip: ImmutableList<String> = persistentListOf(),
    enableTextSelection: Boolean = false,
    onTextTapFeedback: (() -> Unit)? = null,
    onTextDoubleClick: (() -> Unit)? = null,
) {
    ModernMarkdownRenderer(
        content = content,
        modifier = modifier,
        maxVisibleBlocks = maxVisibleBlocks,
        onTodoClick = onTodoClick,
        todoOverrides = todoOverrides,
        onImageClick = onImageClick,
        onTotalBlocks = onTotalBlocks,
        precomputedRenderPlan = precomputedRenderPlan,
        knownTagsToStrip = knownTagsToStrip,
        enableTextSelection = enableTextSelection,
        onTextTapFeedback = onTextTapFeedback,
        onTextDoubleClick = onTextDoubleClick,
    )
}

@Composable
internal fun MarkdownRendererFallback(
    content: String,
    modifier: Modifier = Modifier,
    enableTextSelection: Boolean = false,
    onTextTapFeedback: (() -> Unit)? = null,
    onTextDoubleClick: (() -> Unit)? = null,
) {
    val normalizedContent = remember(content) { content.normalizeCjkMixedSpacingForDisplay() }
    val textStyle =
        resolveRawMemoPlainTextStyle(
            typography = MaterialTheme.typography,
            text = normalizedContent,
        ).copy(color = MaterialTheme.colorScheme.onSurface)

    MemoParagraphText(
        text = normalizedContent,
        style = textStyle,
        modifier = modifier,
        selectable = enableTextSelection,
        onTapFeedback = onTextTapFeedback,
        onDoubleClick = onTextDoubleClick,
    )
}
