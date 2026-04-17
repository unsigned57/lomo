package com.lomo.ui.component.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.text.rawMemoParagraphSpacing
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.memoPlatformTextHandleColor
import com.lomo.ui.theme.memoPlatformTextSelectionHighlightColor

internal val InputEditorContainerPaddingHorizontal = 16.dp
internal val InputEditorContainerPaddingVertical = 12.dp

@Composable
internal fun InputEditorTextField(
    isExpanded: Boolean,
    showsPlaceholder: Boolean,
    inputValue: TextFieldValue,
    hintText: String,
    focusRequester: FocusRequester,
    onEditorReady: (MemoInputEditText) -> Unit,
    textStyle: TextStyle,
    placeholderTextStyle: TextStyle,
    benchmarkEditorTag: String?,
    modifier: Modifier = Modifier,
    onTextChange: (TextFieldValue) -> Unit,
) {
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val displayStyle =
        remember(textStyle, onSurface) {
            textStyle.copy(color = onSurface)
        }
    val colorScheme = MaterialTheme.colorScheme
    val cursorColor = memoPlatformTextHandleColor(colorScheme).toArgb()
    val selectionHighlightColor = memoPlatformTextSelectionHighlightColor(colorScheme).toArgb()
    val selectionHandleColor = memoPlatformTextHandleColor(colorScheme).toArgb()
    val paragraphSpacingPx = with(density) { rawMemoParagraphSpacing().roundToPx() }
    val minimumContentHeightPx = resolveMemoInputMinimumContentHeightPx(displayStyle, density)
    val maximumContentHeightPx = resolveMemoInputMaximumContentHeightPx(displayStyle, density)
    val minimumContentHeight = with(density) { minimumContentHeightPx.toDp() }
    val maximumContentHeight = with(density) { maximumContentHeightPx.toDp() }
    val minimumContainerHeight = minimumContentHeight + (InputEditorContainerPaddingVertical * 2)
    val maximumContainerHeight = maximumContentHeight + (InputEditorContainerPaddingVertical * 2)
    val compactContainerMaxHeight = maximumContainerHeight.coerceAtLeast(minimumContainerHeight)
    val compactEditorMaxHeight = maximumContentHeight.coerceAtLeast(minimumContentHeight)
    val editorContainerMaxHeight =
        resolveInputEditorMaximumHeight(
            isExpanded = isExpanded,
            compactMaximumHeight = compactContainerMaxHeight,
        )
    val editorContentMaxHeight =
        resolveInputEditorMaximumHeight(
            isExpanded = isExpanded,
            compactMaximumHeight = compactEditorMaxHeight,
        )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(
                    min = minimumContainerHeight,
                    max = editorContainerMaxHeight,
                )
                .benchmarkAnchor(benchmarkEditorTag)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, AppShapes.Large)
                .padding(
                    horizontal = InputEditorContainerPaddingHorizontal,
                    vertical = InputEditorContainerPaddingVertical,
                ),
    ) {
        if (showsPlaceholder) {
            InputEditorPlaceholder(
                hintText = hintText,
                textStyle = placeholderTextStyle,
                minimumContentHeight = minimumContentHeight,
            )
        }
        AndroidView(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = minimumContentHeight,
                        max = editorContentMaxHeight,
                    )
                    .focusRequester(focusRequester),
            factory = { context ->
                createMemoInputEditText(
                    context = context,
                    cursorColor = cursorColor,
                    selectionHighlightColor = selectionHighlightColor,
                    selectionHandleColor = selectionHandleColor,
                    onEditorReady = onEditorReady,
                    onTextChange = onTextChange,
                )
            },
            update = { editText ->
                updateMemoInputEditText(
                    editText = editText,
                    inputValue = inputValue,
                    paragraphSpacingPx = paragraphSpacingPx,
                    displayStyle = displayStyle,
                    density = density,
                    cursorColor = cursorColor,
                    selectionHighlightColor = selectionHighlightColor,
                    selectionHandleColor = selectionHandleColor,
                    usePlatformScrollbars = true,
                    onEditorReady = onEditorReady,
                )
            },
        )
    }
}

private fun resolveInputEditorMaximumHeight(
    isExpanded: Boolean,
    compactMaximumHeight: Dp,
): Dp =
    if (isExpanded) {
        Dp.Unspecified
    } else {
        compactMaximumHeight
    }

@Composable
private fun InputEditorPlaceholder(
    hintText: String,
    textStyle: TextStyle,
    minimumContentHeight: Dp,
) {
    Text(
        text = hintText,
        style = textStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minimumContentHeight),
    )
}
