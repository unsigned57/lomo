package com.lomo.ui.component.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.lomo.ui.text.scriptAwareFor

internal fun resolveMarkdownParagraphTextStyle(
    baseStyle: TextStyle,
    fallbackColor: Color,
    text: CharSequence,
): TextStyle {
    val resolvedColor = if (baseStyle.color == Color.Unspecified) fallbackColor else baseStyle.color
    return baseStyle.copy(color = resolvedColor).scriptAwareFor(text)
}
