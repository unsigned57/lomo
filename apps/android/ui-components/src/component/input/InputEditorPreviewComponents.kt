package com.lomo.ui.component.input

import com.lomo.ui.generated.resources.input_preview_empty
import com.lomo.ui.generated.resources.input_preview_failed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lomo.ui.component.common.ExpressiveLoadingIndicator
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.component.markdown.MarkdownRenderState
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun InputEditorPreviewContent(
    inputText: String,
    renderState: MarkdownRenderState,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = InputSheetTokens.PreviewShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        when (val presentation = resolveInputEditorPreviewPresentation(inputText, renderState)) {
            InputEditorPreviewPresentation.Blank ->
                InputEditorPreviewStatus(stringResource(Res.string.input_preview_empty))
            InputEditorPreviewPresentation.Pending ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator()
                }
            InputEditorPreviewPresentation.Failed ->
                InputEditorPreviewStatus(stringResource(Res.string.input_preview_failed))
            is InputEditorPreviewPresentation.Ready ->
                WithDraggableScrollbar(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(
                                    horizontal = InputSheetTokens.EditorContainerPaddingHorizontal,
                                    vertical = InputSheetTokens.EditorContainerPaddingVertical,
                                ),
                    ) {
                        MarkdownRenderer(
                            document = presentation.document,
                            modifier = Modifier.fillMaxWidth(),
                            enableTextSelection = true,
                        )
                    }
                }
        }
    }
}

@Composable
private fun InputEditorPreviewStatus(text: String) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = InputSheetTokens.PreviewHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal sealed interface InputEditorPreviewPresentation {
    data object Blank : InputEditorPreviewPresentation
    data object Pending : InputEditorPreviewPresentation
    data object Failed : InputEditorPreviewPresentation
    data class Ready(
        val document: com.lomo.domain.model.markdown.MarkdownRenderDocument,
    ) : InputEditorPreviewPresentation
}

internal fun resolveInputEditorPreviewPresentation(
    inputText: String,
    renderState: MarkdownRenderState,
): InputEditorPreviewPresentation =
    if (inputText.isBlank()) {
        InputEditorPreviewPresentation.Blank
    } else {
        when (renderState) {
            MarkdownRenderState.Pending -> InputEditorPreviewPresentation.Pending
            is MarkdownRenderState.Ready -> InputEditorPreviewPresentation.Ready(renderState.document)
            is MarkdownRenderState.Failed -> InputEditorPreviewPresentation.Failed
        }
    }
