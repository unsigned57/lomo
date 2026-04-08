package com.lomo.ui.text

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.core.widget.TextViewCompat

internal fun TextView.applyMemoParagraphAppearance(
    text: CharSequence,
    style: TextStyle,
    density: Density,
    maxLines: Int,
    overflow: TextOverflow,
    selectable: Boolean,
) {
    val layoutPolicy = resolveMemoParagraphLayoutPolicy(text)
    val hasLinks = text.hasPlatformLinks()
    setTextColor(style.color.toArgb())
    setLinkTextColor(currentTextColor)
    gravity = layoutPolicy.gravity
    textAlignment = layoutPolicy.textAlignment
    this.maxLines = maxLines
    ellipsize = overflow.toEllipsize()
    setTextIsSelectable(selectable)
    breakStrategy = layoutPolicy.breakStrategy
    hyphenationFrequency = layoutPolicy.hyphenationFrequency
    justificationMode = layoutPolicy.justificationMode
    typeface = style.resolvePlatformTypeface()
    linksClickable = hasLinks
    when (resolveMemoParagraphMovementMethodPolicy(hasLinks = hasLinks, selectable = selectable)) {
        MemoParagraphMovementMethodPolicy.PreserveExisting -> Unit
        MemoParagraphMovementMethodPolicy.LinkOnly -> movementMethod = LinkMovementMethod.getInstance()
        MemoParagraphMovementMethodPolicy.None -> movementMethod = null
    }

    with(density) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, style.fontSize.toPx())
        if (style.lineHeight != TextUnit.Unspecified) {
            TextViewCompat.setLineHeight(this@applyMemoParagraphAppearance, style.lineHeight.roundToPx())
        }
    }

    if (
        style.letterSpacing.type == TextUnitType.Sp &&
        style.fontSize != TextUnit.Unspecified &&
        style.fontSize.value != 0f
    ) {
        letterSpacing = style.letterSpacing.value / style.fontSize.value
    }
}

internal enum class MemoParagraphMovementMethodPolicy {
    PreserveExisting,
    LinkOnly,
    None,
}

internal fun resolveMemoParagraphMovementMethodPolicy(
    hasLinks: Boolean,
    selectable: Boolean,
): MemoParagraphMovementMethodPolicy =
    when {
        selectable -> MemoParagraphMovementMethodPolicy.PreserveExisting
        hasLinks -> MemoParagraphMovementMethodPolicy.LinkOnly
        else -> MemoParagraphMovementMethodPolicy.None
    }
