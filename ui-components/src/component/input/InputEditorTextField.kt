package com.lomo.ui.component.input

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.theme.memoPlatformTextHandleColor

@Composable
internal fun InputEditorTextField(
    isExpanded: Boolean,
    showsPlaceholder: Boolean,
    inputValue: TextFieldValue,
    hintText: String,
    focusRequester: FocusRequester,
    textStyle: TextStyle,
    placeholderTextStyle: TextStyle,
    benchmarkEditorTag: String?,
    modifier: Modifier = Modifier,
    onTextChange: (TextFieldValue) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val displayStyle =
        remember(textStyle, onSurface) {
            textStyle.copy(color = onSurface)
        }
    val colorScheme = MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    val minimumContentHeightPx = resolveMemoInputMinimumContentHeightPx(displayStyle, density)
    val maximumContentHeightPx = resolveMemoInputMaximumContentHeightPx(displayStyle, density)
    val minimumContentHeight = with(density) { minimumContentHeightPx.toDp() }
    val maximumContentHeight = with(density) { maximumContentHeightPx.toDp() }
    val minimumContainerHeight = minimumContentHeight + (InputSheetTokens.EditorContainerPaddingVertical * 2)
    val maximumContainerHeight = maximumContentHeight + (InputSheetTokens.EditorContainerPaddingVertical * 2)
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
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, InputSheetTokens.EditorContainerShape)
                .padding(
                    horizontal = InputSheetTokens.EditorContainerPaddingHorizontal,
                    vertical = InputSheetTokens.EditorContainerPaddingVertical,
                ),
    ) {
        if (showsPlaceholder) {
            InputEditorPlaceholder(
                hintText = hintText,
                textStyle = placeholderTextStyle,
                minimumContentHeight = minimumContentHeight,
            )
        }
        BasicTextField(
            value = inputValue,
            onValueChange = onTextChange,
            modifier =
                Modifier
                    .then(
                        if (isExpanded) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .heightIn(
                                    min = minimumContentHeight,
                                    max = editorContentMaxHeight,
                                )
                        },
                    )
                    .verticalScroll(rememberScrollState())
                    .benchmarkAnchor(benchmarkEditorTag)
                    .focusRequester(focusRequester),
            textStyle = displayStyle,
            cursorBrush = SolidColor(memoPlatformTextHandleColor(colorScheme)),
            minLines = INPUT_EDITOR_MIN_LINES,
            maxLines = if (isExpanded) Int.MAX_VALUE else INPUT_EDITOR_MAX_LINES,
            interactionSource = remember { MutableInteractionSource() },
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
        color = InputSheetTokens.placeholderColor(MaterialTheme.colorScheme),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minimumContentHeight),
    )
}
