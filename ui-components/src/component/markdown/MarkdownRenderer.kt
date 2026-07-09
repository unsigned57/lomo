package com.lomo.ui.component.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.lomo.ui.text.MemoParagraphText
import com.lomo.ui.text.MemoTextSelectionRegistrar
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
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    onTotalBlocks: ((Int) -> Unit)? = null,
    precomputedRenderPlan: ModernMarkdownRenderPlan? = null,
    knownTagsToStrip: ImmutableList<String> = persistentListOf(),
    enableTextSelection: Boolean = false,
    onTextTapFeedback: (() -> Unit)? = null,
    onTextBodyClick: (() -> Unit)? = null,
    onTextDoubleClick: (() -> Unit)? = null,
    onTextLongClick: (() -> Unit)? = null,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)? = null,
) {
    MarkdownRendererContent(
        content = content,
        maxVisibleBlocks = maxVisibleBlocks,
        onTodoClick = onTodoClick,
        todoOverrides = todoOverrides,
        onImageClick = onImageClick,
        mediaPresentationResolver = mediaPresentationResolver,
        onTotalBlocks = onTotalBlocks,
        precomputedRenderPlan = precomputedRenderPlan,
        knownTagsToStrip = knownTagsToStrip,
        enableTextSelection = enableTextSelection,
        textSelectionRegistrar = null,
        onTextTapFeedback = onTextTapFeedback,
        onTextBodyClick = onTextBodyClick,
        onTextDoubleClick = onTextDoubleClick,
        onTextLongClick = onTextLongClick,
        modifier = modifier,
        hideImages = hideImages,
        mediaContent = mediaContent,
    )
}

@Composable
internal fun MarkdownRendererWithTextSelectionRegistrar(
    content: String,
    modifier: Modifier = Modifier,
    maxVisibleBlocks: Int = Int.MAX_VALUE,
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: ImmutableMap<Int, Boolean> = persistentHashMapOf(),
    onImageClick: ((String) -> Unit)? = null,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    onTotalBlocks: ((Int) -> Unit)? = null,
    precomputedRenderPlan: ModernMarkdownRenderPlan? = null,
    knownTagsToStrip: ImmutableList<String> = persistentListOf(),
    enableTextSelection: Boolean = false,
    textSelectionRegistrar: MemoTextSelectionRegistrar? = null,
    onTextTapFeedback: (() -> Unit)? = null,
    onTextBodyClick: (() -> Unit)? = null,
    onTextDoubleClick: (() -> Unit)? = null,
    onTextLongClick: (() -> Unit)? = null,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)? = null,
) {
    MarkdownRendererContent(
        content = content,
        maxVisibleBlocks = maxVisibleBlocks,
        onTodoClick = onTodoClick,
        todoOverrides = todoOverrides,
        onImageClick = onImageClick,
        mediaPresentationResolver = mediaPresentationResolver,
        onTotalBlocks = onTotalBlocks,
        precomputedRenderPlan = precomputedRenderPlan,
        knownTagsToStrip = knownTagsToStrip,
        enableTextSelection = enableTextSelection,
        textSelectionRegistrar = textSelectionRegistrar,
        onTextTapFeedback = onTextTapFeedback,
        onTextBodyClick = onTextBodyClick,
        onTextDoubleClick = onTextDoubleClick,
        onTextLongClick = onTextLongClick,
        modifier = modifier,
        hideImages = hideImages,
        mediaContent = mediaContent,
    )
}

@Composable
private fun MarkdownRendererContent(
    content: String,
    maxVisibleBlocks: Int,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    onTotalBlocks: ((Int) -> Unit)?,
    precomputedRenderPlan: ModernMarkdownRenderPlan?,
    knownTagsToStrip: ImmutableList<String>,
    enableTextSelection: Boolean,
    textSelectionRegistrar: MemoTextSelectionRegistrar?,
    onTextTapFeedback: (() -> Unit)?,
    onTextBodyClick: (() -> Unit)?,
    onTextDoubleClick: (() -> Unit)?,
    onTextLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    hideImages: Boolean = false,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)? = null,
) {
    ModernMarkdownRenderer(
        content = content,
        modifier = modifier,
        maxVisibleBlocks = maxVisibleBlocks,
        onTodoClick = onTodoClick,
        todoOverrides = todoOverrides,
        onImageClick = onImageClick,
        mediaPresentationResolver = mediaPresentationResolver,
        onTotalBlocks = onTotalBlocks,
        precomputedRenderPlan = precomputedRenderPlan,
        knownTagsToStrip = knownTagsToStrip,
        enableTextSelection = enableTextSelection,
        textSelectionRegistrar = textSelectionRegistrar,
        onTextTapFeedback = onTextTapFeedback,
        onTextBodyClick = onTextBodyClick,
        onTextDoubleClick = onTextDoubleClick,
        onTextLongClick = onTextLongClick,
        hideImages = hideImages,
        mediaContent = mediaContent,
    )
}

@Composable
internal fun MarkdownRendererFallback(
    content: String,
    modifier: Modifier = Modifier,
    enableTextSelection: Boolean = false,
    textSelectionRegistrar: MemoTextSelectionRegistrar? = null,
    onTextTapFeedback: (() -> Unit)? = null,
    onTextBodyClick: (() -> Unit)? = null,
    onTextDoubleClick: (() -> Unit)? = null,
    onTextLongClick: (() -> Unit)? = null,
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
        selectionRegistrar = textSelectionRegistrar,
        onTapFeedback = onTextTapFeedback,
        onBodyClick = onTextBodyClick,
        onDoubleClick = onTextDoubleClick,
        onLongClick = onTextLongClick,
    )
}
