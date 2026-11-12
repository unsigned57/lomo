package com.lomo.ui.component.input

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.theme.memoPlatformTextHandleColor
import kotlin.math.ceil

/**
 * The writing surface fills whatever height its canvas grants; the panel owns that height. The field
 * reports its text's natural height (with the canvas padding) so the panel can morph from it.
 */
@Composable
internal fun InputEditorTextField(
    enabled: Boolean,
    state: InputEditorTextFieldState,
    placeholderAlpha: () -> Float,
    onContentHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val displayStyle =
        remember(state.textStyle, colorScheme.onSurface) {
            state.textStyle.copy(color = colorScheme.onSurface)
        }
    val verticalPaddingPx =
        with(LocalDensity.current) { (InputSheetTokens.EditorContainerPaddingVertical * 2).roundToPx() }

    // Min constraints pass through so the whole canvas is the field's touch target.
    Box(
        modifier =
        modifier
            .then(if (enabled) Modifier else Modifier.clearAndSetSemantics { })
            .padding(
                horizontal = InputSheetTokens.EditorContainerPaddingHorizontal,
                vertical = InputSheetTokens.EditorContainerPaddingVertical,
            ),
        propagateMinConstraints = true,
    ) {
        if (state.showsPlaceholder) {
            Text(
                text = state.hintText,
                style = state.placeholderTextStyle,
                color = InputSheetTokens.placeholderColor(colorScheme),
                // The hint never sizes the canvas: the morph starts from the field's own height.
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = placeholderAlpha() },
            )
        }
        BasicTextField(
            enabled = enabled,
            state = state.inputState,
            inputTransformation = state.inputTransformation,
            outputTransformation = rememberInputEditorHiddenText(state.inputValue.text, state.hiddenTextRanges),
            scrollState = rememberScrollState(),
            modifier =
            Modifier
                .fillMaxWidth()
                .benchmarkAnchor(state.benchmarkEditorTag)
                .focusRequester(state.focusRequester),
            textStyle = displayStyle,
            cursorBrush = SolidColor(memoPlatformTextHandleColor(colorScheme)),
            lineLimits = TextFieldLineLimits.MultiLine(),
            onTextLayout = { layoutResult ->
                // The field scrolls inside its canvas, so the paragraph height is the full text height.
                layoutResult()?.let { result ->
                    onContentHeightChanged(ceil(result.multiParagraph.height).toInt() + verticalPaddingPx)
                }
            },
            interactionSource = remember { MutableInteractionSource() },
        )
    }
}
