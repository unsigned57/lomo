package com.lomo.ui.component.input

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

private const val INPUT_CURSOR_FULL_ALPHA = 0xFF
private const val INPUT_CURSOR_WIDTH_DP = 2f
private const val INPUT_CURSOR_MIN_HEIGHT_DP = 18f

internal data class FallbackCursorDrawableSize(
    val widthPx: Int,
    val heightPx: Int,
)

internal enum class MemoInputCursorStylingStrategy {
    Reflection,
    DrawableProperty,
}

internal fun resolveMemoInputCursorStylingStrategy(sdkInt: Int): MemoInputCursorStylingStrategy =
    if (sdkInt >= android.os.Build.VERSION_CODES.Q) {
        MemoInputCursorStylingStrategy.DrawableProperty
    } else {
        MemoInputCursorStylingStrategy.Reflection
    }

internal fun resolveFallbackCursorDrawableSize(
    density: Float,
    textSizePx: Float,
): FallbackCursorDrawableSize =
    FallbackCursorDrawableSize(
        widthPx = (density * INPUT_CURSOR_WIDTH_DP).roundToInt().coerceAtLeast(2),
        heightPx =
            maxOf(
                textSizePx.roundToInt().coerceAtLeast(1),
                (density * INPUT_CURSOR_MIN_HEIGHT_DP).roundToInt().coerceAtLeast(1),
            ),
    )

internal fun EditText.buildCursorDrawable(color: Int): GradientDrawable =
    GradientDrawable().apply {
        val size =
            resolveFallbackCursorDrawableSize(
                density = resources.displayMetrics.density,
                textSizePx = textSize,
            )
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        setSize(size.widthPx, size.heightPx)
        alpha = INPUT_CURSOR_FULL_ALPHA
    }

@RequiresApi(Build.VERSION_CODES.Q)
internal fun EditText.applyCursorDrawableProperty(color: Int) {
    textCursorDrawable = buildCursorDrawable(color)
}

@SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi")
internal fun EditText.applyReflectionCursorDrawables(cursorDrawable: GradientDrawable) {
    val editorField = TextView::class.java.getDeclaredField("mEditor").apply { isAccessible = true }
    val editor = editorField.get(this)
    val cursorDrawableField = editor.javaClass.getDeclaredField("mCursorDrawable").apply { isAccessible = true }
    val secondDrawable =
        (cursorDrawable.constantState?.newDrawable()?.mutate() as? GradientDrawable)
            ?: buildCursorDrawable(cursorDrawable.color?.defaultColor ?: currentTextColor)
    cursorDrawableField.set(editor, arrayOf(cursorDrawable, secondDrawable))
    runCatching {
        TextView::class.java.getDeclaredField("mCursorDrawableRes").apply { isAccessible = true }.setInt(this, 0)
    }
}
