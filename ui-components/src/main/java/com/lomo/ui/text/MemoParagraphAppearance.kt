package com.lomo.ui.text

import android.annotation.SuppressLint
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.core.widget.TextViewCompat

@SuppressLint("ClickableViewAccessibility")
internal fun TextView.applyMemoParagraphAppearance(
    text: CharSequence,
    style: TextStyle,
    density: Density,
    maxLines: Int,
    overflow: TextOverflow,
    selectable: Boolean,
    selectionHighlightColor: Int,
    selectionHandleColor: Int,
) {
    val layoutPolicy = resolveMemoParagraphLayoutPolicy(text)
    val hasLinks = text.hasPlatformLinks()
    val interactionPolicy = resolveMemoParagraphInteractionPolicy(hasLinks = hasLinks, selectable = selectable)
    setTextColor(style.color.toArgb())
    gravity = layoutPolicy.gravity
    textAlignment = layoutPolicy.textAlignment
    this.maxLines = maxLines
    ellipsize = overflow.toEllipsize()
    setTextIsSelectable(selectable)
    highlightColor = selectionHighlightColor
    breakStrategy = layoutPolicy.breakStrategy
    hyphenationFrequency = layoutPolicy.hyphenationFrequency
    justificationMode =
        if (selectable) {
            android.text.Layout.JUSTIFICATION_MODE_NONE
        } else {
            layoutPolicy.justificationMode
        }
    typeface = style.resolvePlatformTypeface()
    linksClickable = hasLinks
    applySelectionHandleColor(selectionHandleColor)
    when (interactionPolicy.movementMethodPolicy) {
        MemoParagraphMovementMethodPolicy.PreserveExisting -> Unit
        MemoParagraphMovementMethodPolicy.LinkOnly -> movementMethod = LinkMovementMethod.getInstance()
        MemoParagraphMovementMethodPolicy.None -> movementMethod = null
    }
    setOnTouchListener(
        if (interactionPolicy.enableManualLinkTapHandling) {
            MemoParagraphSelectableLinkTouchListener
        } else {
            null
        },
    )

    with(density) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, style.fontSize.toPx())
        if (style.lineHeight != TextUnit.Unspecified) {
            TextViewCompat.setLineHeight(this@applyMemoParagraphAppearance, style.lineHeight.roundToPx())
        }
    }

    resolveMemoParagraphPlatformLetterSpacing(
        style = style,
    )?.let { resolvedLetterSpacing ->
        letterSpacing = resolvedLetterSpacing
    }
}

internal fun resolveMemoParagraphPlatformLetterSpacing(
    style: TextStyle,
): Float? =
    when {
        style.letterSpacing.type == TextUnitType.Sp &&
            style.fontSize != TextUnit.Unspecified &&
            style.fontSize.value != 0f -> {
            style.letterSpacing.value / style.fontSize.value
        }

        else -> null
    }

internal data class MemoParagraphInteractionPolicy(
    val movementMethodPolicy: MemoParagraphMovementMethodPolicy,
    val enableManualLinkTapHandling: Boolean,
)

internal enum class MemoParagraphMovementMethodPolicy {
    PreserveExisting,
    LinkOnly,
    None,
}

internal fun resolveMemoParagraphInteractionPolicy(
    hasLinks: Boolean,
    selectable: Boolean,
): MemoParagraphInteractionPolicy =
    MemoParagraphInteractionPolicy(
        movementMethodPolicy = resolveMemoParagraphMovementMethodPolicy(hasLinks = hasLinks, selectable = selectable),
        enableManualLinkTapHandling = hasLinks && selectable,
    )

internal fun resolveMemoParagraphMovementMethodPolicy(
    hasLinks: Boolean,
    selectable: Boolean,
): MemoParagraphMovementMethodPolicy =
    when {
        selectable -> MemoParagraphMovementMethodPolicy.PreserveExisting
        hasLinks -> MemoParagraphMovementMethodPolicy.LinkOnly
        else -> MemoParagraphMovementMethodPolicy.None
    }

private object MemoParagraphSelectableLinkTouchListener : View.OnTouchListener {
    override fun onTouch(
        view: View,
        event: MotionEvent,
    ): Boolean {
        val textView = view as? TextView ?: return false
        if (event.actionMasked != MotionEvent.ACTION_UP) return false
        if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) return false
        if (textView.hasSelection() && textView.selectionStart != textView.selectionEnd) return false

        val linkSpan = textView.findClickableSpanAt(event) ?: return false
        textView.performClick()
        linkSpan.onClick(textView)
        return true
    }
}

private fun TextView.findClickableSpanAt(event: MotionEvent): ClickableSpan? {
    val spannedText = text as? Spanned ?: return null
    val layout = layout ?: return null
    val x = event.x.toInt() - totalPaddingLeft + scrollX
    val y = event.y.toInt() - totalPaddingTop + scrollY
    if (x < 0 || y < 0 || x > layout.width || y > layout.height) return null

    val line = layout.getLineForVertical(y)
    val offset = layout.getOffsetForHorizontal(line, x.toFloat())
    return spannedText.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
}
