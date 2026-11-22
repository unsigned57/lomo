package com.lomo.ui.component.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
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
    collapsedSummary: String,
    state: MemoCardBodyState,
    selectionRegistrar: MemoTextSelectionRegistrar?,
) {
    if (visualState == MemoCardBodyVisualState.CollapsedSummary) {
        MemoCardCollapsedBody {
            MemoCardCollapsedSummary(
                collapsedSummary = collapsedSummary,
                allowFreeTextCopy = state.allowFreeTextCopy,
                onBodyClick = state.onBodyClick,
                onLongClick = state.onLongClick,
                selectionRegistrar = selectionRegistrar,
            )
        }
        return
    }

    // Expanded and CollapsedMarkdownPreview share a single MarkdownIrRenderer slot so expanding,
    // collapsing, or toggling todos never disposes and recreates the markdown subtree.
    val isCollapsedPreview = visualState == MemoCardBodyVisualState.CollapsedMarkdownPreview
    Box(
        modifier =
        if (isCollapsedPreview) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = MemoCardTokens.CollapsedBodyMaxHeight)
                .clipToBounds()
        } else {
            Modifier.fillMaxWidth()
        },
    ) {
        MemoCardMarkdownContent(
            state = state,
            selectionRegistrar = selectionRegistrar,
        )
        if (isCollapsedPreview) {
            MemoCardCollapsedOverlay()
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
private fun MemoCardMarkdownContent(state: MemoCardBodyState, selectionRegistrar: MemoTextSelectionRegistrar?) {
    MarkdownIrRenderer(
        document = state.renderDocument,
        presentationPlan = state.presentationPlan,
        presentationPolicy = MarkdownPresentationPolicy.MEMO_CARD,
        modifier = Modifier.fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .onSizeChanged { state.onBodyHeightChanged(it.height) }
            .padding(vertical = MemoCardTokens.BodyVerticalPadding),
        onTaskClick = state.onTodoClick,
        onImageClick = state.onImageClick,
        mediaPresentationResolver = state.mediaPresentationResolver,
        enableTextSelection = state.allowFreeTextCopy,
        textSelectionRegistrar = selectionRegistrar,
        onTextBodyClick = state.onBodyClick,
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
                        MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ),
            ),
    )
}
