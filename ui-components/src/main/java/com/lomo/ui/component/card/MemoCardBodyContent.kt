package com.lomo.ui.component.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.lomo.ui.component.markdown.MarkdownMediaPresentation
import com.lomo.ui.component.markdown.MarkdownMediaPresentationResolver
import com.lomo.ui.component.markdown.MarkdownRendererWithTextSelectionRegistrar
import com.lomo.ui.text.MemoTextSelectionRegistrar
import com.lomo.ui.text.MemoTextSelectionScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf

@Composable
internal fun MemoCardBodyContent(
    collapsedPreviewMode: MemoCardCollapsedPreviewMode,
    collapsedSummary: String,
    allowFreeTextCopy: Boolean,
    onTapFeedback: (() -> Unit)?,
    onBodyClick: (() -> Unit)?,
    onDoubleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    processedContent: String,
    precomputedRenderPlan: com.lomo.ui.component.markdown.ModernMarkdownRenderPlan?,
    tags: ImmutableList<String>,
    isExpanded: Boolean,
    isCollapsedPreview: Boolean,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    bodyTransitionMode: MemoCardBodyTransitionMode,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    val bodyContent: @Composable (MemoTextSelectionRegistrar?) -> Unit = { selectionRegistrar ->
        when (bodyTransitionMode) {
            MemoCardBodyTransitionMode.Snap -> {
                MemoCardBodyStateContent(
                    visualState =
                        resolveMemoCardBodyVisualState(
                            isExpanded = !isCollapsedPreview,
                            collapsedPreviewMode = collapsedPreviewMode,
                        ),
                    collapsedPreviewMode = collapsedPreviewMode,
                    collapsedSummary = collapsedSummary,
                    allowFreeTextCopy = allowFreeTextCopy,
                    onTapFeedback = onTapFeedback,
                    onBodyClick = onBodyClick,
                    onDoubleClick = onDoubleClick,
                    onLongClick = onLongClick,
                    processedContent = processedContent,
                    precomputedRenderPlan = precomputedRenderPlan,
                    tags = tags,
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    onImageClick = onImageClick,
                    mediaPresentationResolver = mediaPresentationResolver,
                    mediaContent = mediaContent,
                    selectionRegistrar = selectionRegistrar,
                )
            }

            MemoCardBodyTransitionMode.StateContentTransform -> {
                val targetVisualState =
                    resolveMemoCardBodyVisualState(
                        isExpanded = isExpanded,
                        collapsedPreviewMode =
                            resolveMemoCardBodyCollapsedTargetPreviewMode(
                                bodyTransitionMode = bodyTransitionMode,
                                currentPreviewMode = collapsedPreviewMode,
                                hasProcessedContent = processedContent.isNotBlank(),
                                collapsedSummary = collapsedSummary,
                            ),
                    )
                MemoCardBodyStateContent(
                    visualState = targetVisualState,
                    collapsedPreviewMode = collapsedPreviewMode,
                    collapsedSummary = collapsedSummary,
                    allowFreeTextCopy = allowFreeTextCopy,
                    onTapFeedback = onTapFeedback,
                    onBodyClick = onBodyClick,
                    onDoubleClick = onDoubleClick,
                    onLongClick = onLongClick,
                    processedContent = processedContent,
                    precomputedRenderPlan = precomputedRenderPlan,
                    tags = tags,
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    onImageClick = onImageClick,
                    mediaPresentationResolver = mediaPresentationResolver,
                    mediaContent = mediaContent,
                    selectionRegistrar = selectionRegistrar,
                )
            }
        }
    }

    MemoTextSelectionScope(
        enabled = allowFreeTextCopy,
        modifier = Modifier.fillMaxWidth(),
    ) { selectionRegistrar ->
        bodyContent(selectionRegistrar)
    }
}

@Composable
private fun MemoCardBodyStateContent(
    visualState: MemoCardBodyVisualState,
    collapsedPreviewMode: MemoCardCollapsedPreviewMode,
    collapsedSummary: String,
    allowFreeTextCopy: Boolean,
    onTapFeedback: (() -> Unit)?,
    onBodyClick: (() -> Unit)?,
    onDoubleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    processedContent: String,
    precomputedRenderPlan: com.lomo.ui.component.markdown.ModernMarkdownRenderPlan?,
    tags: ImmutableList<String>,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    selectionRegistrar: MemoTextSelectionRegistrar?,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    when (visualState) {
        MemoCardBodyVisualState.Expanded -> {
            MemoCardMarkdownContent(
                processedContent = processedContent,
                precomputedRenderPlan = precomputedRenderPlan,
                tags = tags,
                isCollapsedPreview = false,
                allowFreeTextCopy = allowFreeTextCopy,
                onTapFeedback = onTapFeedback,
                onBodyClick = onBodyClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
                mediaPresentationResolver = mediaPresentationResolver,
                mediaContent = mediaContent,
                selectionRegistrar = selectionRegistrar,
            )
        }

        MemoCardBodyVisualState.CollapsedSummary -> {
            MemoCardCollapsedBody {
                MemoCardCollapsedSummary(
                    collapsedSummary = collapsedSummary,
                    allowFreeTextCopy = allowFreeTextCopy,
                    onTapFeedback = onTapFeedback,
                    onBodyClick = onBodyClick,
                    onDoubleClick = onDoubleClick,
                    onLongClick = onLongClick,
                    selectionRegistrar = selectionRegistrar,
                )
            }
        }

        MemoCardBodyVisualState.CollapsedMarkdownPreview -> {
            MemoCardCollapsedBody {
                MemoCardMarkdownContent(
                    processedContent = processedContent,
                    precomputedRenderPlan = precomputedRenderPlan,
                    tags = tags,
                    isCollapsedPreview = collapsedPreviewMode == MemoCardCollapsedPreviewMode.MarkdownPreview,
                    allowFreeTextCopy = allowFreeTextCopy,
                    onTapFeedback = onTapFeedback,
                    onBodyClick = onBodyClick,
                    onDoubleClick = onDoubleClick,
                    onLongClick = onLongClick,
                    onTodoClick = onTodoClick,
                    todoOverrides = todoOverrides,
                    onImageClick = onImageClick,
                    mediaPresentationResolver = mediaPresentationResolver,
                    mediaContent = mediaContent,
                    selectionRegistrar = selectionRegistrar,
                )
            }
        }
    }
}

@Composable
private fun MemoCardCollapsedBody(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = MemoCardTokens.CollapsedBodyMaxHeight)
                .clipToBounds(),
    ) {
        content()
        MemoCardCollapsedOverlay()
    }
}

@Composable
private fun MemoCardMarkdownContent(
    processedContent: String,
    precomputedRenderPlan: com.lomo.ui.component.markdown.ModernMarkdownRenderPlan?,
    tags: ImmutableList<String>,
    isCollapsedPreview: Boolean,
    allowFreeTextCopy: Boolean,
    onTapFeedback: (() -> Unit)?,
    onBodyClick: (() -> Unit)?,
    onDoubleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
    mediaPresentationResolver: MarkdownMediaPresentationResolver?,
    selectionRegistrar: MemoTextSelectionRegistrar?,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)?,
) {
    MarkdownRendererWithTextSelectionRegistrar(
        content = processedContent,
        precomputedRenderPlan = precomputedRenderPlan,
        knownTagsToStrip = if (precomputedRenderPlan == null) tags else persistentListOf(),
        modifier = Modifier.fillMaxWidth().padding(vertical = MemoCardTokens.BodyVerticalPadding),
        maxVisibleBlocks = if (isCollapsedPreview) COLLAPSED_MAX_VISIBLE_BLOCKS else Int.MAX_VALUE,
        onTodoClick = onTodoClick,
        todoOverrides = todoOverrides,
        onImageClick = onImageClick,
        mediaPresentationResolver = mediaPresentationResolver,
        enableTextSelection = allowFreeTextCopy,
        textSelectionRegistrar = selectionRegistrar,
        onTextTapFeedback = onTapFeedback,
        onTextBodyClick = onBodyClick,
        onTextDoubleClick = onDoubleClick,
        onTextLongClick = onLongClick,
        mediaContent = mediaContent,
    )
}

@Composable
private fun BoxScope.MemoCardCollapsedOverlay() {
    Box(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(MemoCardTokens.CollapsedBodyOverlayHeight)
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surfaceContainer,
                                ),
                        ),
                ),
    )
}
