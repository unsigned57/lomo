package com.lomo.ui.component.input

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.widget.EditText
import android.widget.TextView.BufferType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.roundToInt

internal const val INPUT_EDITOR_MIN_LINES = 3
internal const val INPUT_EDITOR_MAX_LINES = 10

internal class MemoInputEditText(
    context: Context,
) : EditText(context) {
    var isUpdatingFromModel: Boolean = false
    var onSelectionChangedListener: (() -> Unit)? = null
    var lastAppliedParagraphSpacingPx: Int? = null

    override fun onSelectionChanged(
        selStart: Int,
        selEnd: Int,
    ) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChangedListener?.invoke()
    }

    fun currentTextFieldValue(): TextFieldValue =
        TextFieldValue(
            text = text?.toString().orEmpty(),
            selection = TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0)),
        )
}

internal fun MemoInputEditText.syncWith(inputValue: TextFieldValue) {
    val currentText = text?.toString().orEmpty()
    val desiredText = inputValue.text

    if (currentText != desiredText) {
        setText(desiredText, BufferType.EDITABLE)
        restoreSelection(inputValue.selection, desiredText.length)
        return
    }

    val desiredStart = inputValue.selection.start.coerceIn(0, desiredText.length)
    val desiredEnd = inputValue.selection.end.coerceIn(0, desiredText.length)
    if (selectionStart != desiredStart || selectionEnd != desiredEnd) {
        restoreSelection(inputValue.selection, desiredText.length)
    }
}

internal fun shouldReplaceMemoInputPresentationText(
    currentText: CharSequence,
    desiredText: CharSequence,
    lastAppliedParagraphSpacingPx: Int?,
    desiredParagraphSpacingPx: Int,
): Boolean =
    currentText.toString() != desiredText.toString() ||
        lastAppliedParagraphSpacingPx != desiredParagraphSpacingPx

internal fun buildRawMemoEditorPresentationText(
    text: String,
    paragraphSpacingPx: Int,
): Editable {
    val editable = Editable.Factory.getInstance().newEditable(text)
    rawMemoParagraphGapRanges(text).forEach { range ->
        editable.setSpan(
            RawMemoParagraphGapSpan(paragraphSpacingPx),
            range.first,
            range.last + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return editable
}

internal fun EditText.clearCursorDrawableIfSupported() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        setTextCursorDrawable(null)
    }
}

internal fun EditText.setCursorColor(color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            textCursorDrawable?.setTint(color)
        }.onFailure {
            refreshCursorStyle()
        }
        return
    }
    refreshCursorStyle()
}

private fun MemoInputEditText.restoreSelection(
    selection: TextRange,
    textLength: Int,
) {
    val start = selection.start.coerceIn(0, textLength)
    val end = selection.end.coerceIn(0, textLength)
    setSelection(start, end)
}

private fun rawMemoParagraphGapRanges(text: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    return Regex("""(?:\n[ \t]*){2,}""")
        .findAll(text)
        .map { match -> (match.range.first + 1)..match.range.last }
        .toList()
}

private fun EditText.refreshCursorStyle() {
    runCatching {
        setTextColor(currentTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
    }
}

private class RawMemoParagraphGapSpan(
    private val paragraphSpacingPx: Int,
) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence?,
        start: Int,
        end: Int,
        spanstartv: Int,
        v: Int,
        fm: android.graphics.Paint.FontMetricsInt,
    ) {
        val targetHeight = paragraphSpacingPx.coerceAtLeast(1)
        val currentHeight = fm.descent - fm.ascent
        if (currentHeight <= 0) return

        val scaledDescent =
            (fm.descent * (targetHeight.toFloat() / currentHeight))
                .roundToInt()
        fm.descent = scaledDescent
        fm.bottom = scaledDescent
        fm.ascent = scaledDescent - targetHeight
        fm.top = fm.ascent
    }
}
