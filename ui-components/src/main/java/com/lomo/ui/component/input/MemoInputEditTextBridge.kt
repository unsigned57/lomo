package com.lomo.ui.component.input

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView.BufferType
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.core.widget.TextViewCompat
import com.lomo.ui.text.resolvePlatformTypeface
import com.lomo.ui.text.toEllipsize
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

internal fun EditText.setCursorColor(color: Int) {
    isCursorVisible = true
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        runCatching {
            applyCursorDrawableProperty(color)
            postInvalidateOnAnimation()
        }.onFailure {
            refreshCursorStyle()
        }
    } else {
        runCatching {
            applyReflectionCursorDrawables(buildCursorDrawable(color))
            postInvalidateOnAnimation()
        }.onFailure {
            refreshCursorStyle()
        }
    }
}

internal fun createMemoInputEditText(
    context: Context,
    cursorColor: Int,
    onEditorReady: (MemoInputEditText) -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
): MemoInputEditText =
    MemoInputEditText(context).apply {
        val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
        onEditorReady(this)
        layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        background = null
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        setTextIsSelectable(true)
        isCursorVisible = true
        isSingleLine = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            isFallbackLineSpacing = false
        }
        minLines = INPUT_EDITOR_MIN_LINES
        maxLines = INPUT_EDITOR_MAX_LINES
        gravity = Gravity.START or Gravity.TOP
        movementMethod = ArrowKeyMovementMethod.getInstance()
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        overScrollMode = View.OVER_SCROLL_NEVER
        highlightColor = cursorColor
        onFocusChangeListener =
            View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    setCursorColor(cursorColor)
                    post {
                        requestFocusFromTouch()
                        @Suppress("DEPRECATION")
                        inputMethodManager?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    if (isUpdatingFromModel) return
                    onTextChange(currentTextFieldValue())
                }
            },
        )
        onSelectionChangedListener = {
            if (!isUpdatingFromModel) {
                onTextChange(currentTextFieldValue())
            }
        }
    }

internal fun updateMemoInputEditText(
    editText: MemoInputEditText,
    inputValue: TextFieldValue,
    paragraphSpacingPx: Int,
    displayStyle: TextStyle,
    density: Density,
    cursorColor: Int,
    onEditorReady: (MemoInputEditText) -> Unit,
) {
    onEditorReady(editText)
    val minimumContentHeightPx = resolveMemoInputMinimumContentHeightPx(displayStyle, density)
    editText.isUpdatingFromModel = true
    editText.minimumHeight = minimumContentHeightPx
    val shouldReplacePresentation =
        shouldReplaceMemoInputPresentationText(
            currentText = editText.text ?: "",
            desiredText = inputValue.text,
            lastAppliedParagraphSpacingPx = editText.lastAppliedParagraphSpacingPx,
            desiredParagraphSpacingPx = paragraphSpacingPx,
        )
    if (shouldReplacePresentation) {
        editText.applyMemoInputParagraphTextStyle(
            text = buildRawMemoEditorPresentationText(inputValue.text, paragraphSpacingPx),
            style = displayStyle,
            density = density,
            maxLines = INPUT_EDITOR_MAX_LINES,
            overflow = TextOverflow.Clip,
        )
        editText.lastAppliedParagraphSpacingPx = paragraphSpacingPx
    } else {
        editText.applyMemoInputParagraphAppearance(
            text = editText.text ?: "",
            style = displayStyle,
            density = density,
            maxLines = INPUT_EDITOR_MAX_LINES,
            overflow = TextOverflow.Clip,
        )
    }
    editText.hint = null
    editText.syncWith(inputValue)
    editText.setCursorColor(cursorColor)
    editText.isUpdatingFromModel = false
    if (editText.hasFocus()) {
        editText.post { editText.setCursorColor(cursorColor) }
    }
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
        invalidate()
    }
}

private fun EditText.applyMemoInputParagraphTextStyle(
    text: CharSequence,
    style: TextStyle,
    density: Density,
    maxLines: Int,
    overflow: TextOverflow,
) {
    setText(text, BufferType.EDITABLE)
    applyMemoInputParagraphAppearance(
        text = text,
        style = style,
        density = density,
        maxLines = maxLines,
        overflow = overflow,
    )
}

private fun EditText.applyMemoInputParagraphAppearance(
    text: CharSequence,
    style: TextStyle,
    density: Density,
    maxLines: Int,
    overflow: TextOverflow,
) {
    val layoutPolicy = resolveMemoInputParagraphLayoutPolicy(text)
    setTextColor(style.color.toArgb())
    gravity = layoutPolicy.gravity
    textAlignment = layoutPolicy.textAlignment
    this.maxLines = maxLines
    ellipsize = overflow.toEllipsize()
    setTextIsSelectable(true)
    breakStrategy = layoutPolicy.breakStrategy
    hyphenationFrequency = layoutPolicy.hyphenationFrequency
    justificationMode = layoutPolicy.justificationMode
    typeface = style.resolvePlatformTypeface()

    with(density) {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, style.fontSize.toPx())
        if (style.lineHeight != TextUnit.Unspecified) {
            TextViewCompat.setLineHeight(this@applyMemoInputParagraphAppearance, style.lineHeight.roundToPx())
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
