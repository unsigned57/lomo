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
import com.lomo.ui.component.markdown.MarkdownIrRenderer
import com.lomo.ui.component.markdown.MarkdownPresentationPolicy
import com.lomo.ui.text.MemoTextSelectionRegistrar
import com.lomo.ui.text.MemoTextSelectionScope

@Composable
internal fun MemoCardBodyContent(
    collapsedPreviewMode: MemoCardCollapsedPreviewMode,
    collapsedSummary: String,
    processedContent: String,
    isExpanded: Boolean,
    isCollapsedPreview: Boolean,
    bodyTransitionMode: MemoCardBodyTransitionMode,
    state: MemoCardBodyState,
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
                    state = state,
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
                    state = state,
                    selectionRegistrar = selectionRegistrar,
                )
            }
        }
    }

    MemoTextSelectionScope(
        enabled = state.allowFreeTextCopy,
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
    state: MemoCardBodyState,
    selectionRegistrar: MemoTextSelectionRegistrar?,
) {
    when (visualState) {
        MemoCardBodyVisualState.Expanded -> {
            MemoCardMarkdownContent(
                state = state,
                isCollapsedPreview = false,
                selectionRegistrar = selectionRegistrar,
            )
        }

        MemoCardBodyVisualState.CollapsedSummary -> {
            MemoCardCollapsedBody {
                MemoCardCollapsedSummary(
                    collapsedSummary = collapsedSummary,
                    allowFreeTextCopy = state.allowFreeTextCopy,
                    onTapFeedback = state.onTapFeedback,
                    onBodyClick = state.onBodyClick,
                    onDoubleClick = state.onDoubleClick,
                    onLongClick = state.onLongClick,
                    selectionRegistrar = selectionRegistrar,
                )
            }
        }

        MemoCardBodyVisualState.CollapsedMarkdownPreview -> {
            MemoCardCollapsedBody {
                MemoCardMarkdownContent(
                    state = state,
                    isCollapsedPreview = collapsedPreviewMode == MemoCardCollapsedPreviewMode.MarkdownPreview,
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
    state: MemoCardBodyState,
    isCollapsedPreview: Boolean,
    selectionRegistrar: MemoTextSelectionRegistrar?,
) {
    MarkdownIrRenderer(
        document = state.renderDocument,
        presentationPlan = state.presentationPlan,
        presentationPolicy = MarkdownPresentationPolicy.MEMO_CARD,
        modifier = Modifier.fillMaxWidth().padding(vertical = MemoCardTokens.BodyVerticalPadding),
        maxVisibleBlocks = if (isCollapsedPreview) COLLAPSED_MAX_VISIBLE_BLOCKS else Int.MAX_VALUE,
        onTaskClick = state.onTodoClick,
        onImageClick = state.onImageClick,
        mediaPresentationResolver = state.mediaPresentationResolver,
        enableTextSelection = state.allowFreeTextCopy,
        textSelectionRegistrar = selectionRegistrar,
        onTextTapFeedback = state.onTapFeedback,
        onTextBodyClick = state.onBodyClick,
        onTextDoubleClick = state.onDoubleClick,
        onTextLongClick = state.onLongClick,
        mediaContent = state.mediaContent,
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
