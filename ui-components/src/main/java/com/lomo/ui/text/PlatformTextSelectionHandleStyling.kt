package com.lomo.ui.text

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.TextView

internal enum class PlatformTextSelectionHandleStyling {
    Reflection,
    DrawableProperty,
}

internal fun resolvePlatformTextSelectionHandleStylingStrategy(sdkInt: Int): PlatformTextSelectionHandleStyling =
    if (sdkInt >= Build.VERSION_CODES.Q) {
        PlatformTextSelectionHandleStyling.DrawableProperty
    } else {
        PlatformTextSelectionHandleStyling.Reflection
    }

internal fun TextView.applySelectionHandleColor(color: Int) {
    when (resolvePlatformTextSelectionHandleStylingStrategy(Build.VERSION.SDK_INT)) {
        PlatformTextSelectionHandleStyling.DrawableProperty -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                applySelectionHandleDrawablesProperty(color)
            }
        }

        PlatformTextSelectionHandleStyling.Reflection -> {
            runCatching {
                applySelectionHandleDrawablesReflection(color)
            }
        }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private fun TextView.applySelectionHandleDrawablesProperty(color: Int) {
    val insertionHandle = textSelectHandle?.tintedCopyPreservingState(color) ?: return
    val leftHandle = textSelectHandleLeft?.tintedCopyPreservingState(color) ?: return
    val rightHandle = textSelectHandleRight?.tintedCopyPreservingState(color) ?: return

    setTextSelectHandle(insertionHandle)
    setTextSelectHandleLeft(leftHandle)
    setTextSelectHandleRight(rightHandle)
}

@SuppressLint("BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi")
private fun TextView.applySelectionHandleDrawablesReflection(color: Int) {
    val textViewClass = TextView::class.java
    val insertionHandle =
        resolveReflectedTextViewDrawable(
            textViewClass = textViewClass,
            drawableFieldName = "mTextSelectHandle",
            resourceFieldName = "mTextSelectHandleRes",
        )?.tintedCopyPreservingState(color)
    val leftHandle =
        resolveReflectedTextViewDrawable(
            textViewClass = textViewClass,
            drawableFieldName = "mTextSelectHandleLeft",
            resourceFieldName = "mTextSelectHandleLeftRes",
        )?.tintedCopyPreservingState(color)
    val rightHandle =
        resolveReflectedTextViewDrawable(
            textViewClass = textViewClass,
            drawableFieldName = "mTextSelectHandleRight",
            resourceFieldName = "mTextSelectHandleRightRes",
        )?.tintedCopyPreservingState(color)

    if (insertionHandle != null) {
        textViewClass
            .getDeclaredField("mTextSelectHandle")
            .apply { isAccessible = true }
            .set(this, insertionHandle)
        textViewClass.getDeclaredField("mTextSelectHandleRes").apply { isAccessible = true }.setInt(this, 0)
    }
    if (leftHandle != null) {
        textViewClass
            .getDeclaredField("mTextSelectHandleLeft")
            .apply { isAccessible = true }
            .set(this, leftHandle)
        textViewClass.getDeclaredField("mTextSelectHandleLeftRes").apply { isAccessible = true }.setInt(this, 0)
    }
    if (rightHandle != null) {
        textViewClass
            .getDeclaredField("mTextSelectHandleRight")
            .apply { isAccessible = true }
            .set(this, rightHandle)
        textViewClass.getDeclaredField("mTextSelectHandleRightRes").apply { isAccessible = true }.setInt(this, 0)
    }

    val editor = textViewClass.getDeclaredField("mEditor").apply { isAccessible = true }.get(this) ?: return
    val editorClass = editor.javaClass

    if (insertionHandle != null) {
        editorClass
            .getDeclaredField("mSelectHandleCenter")
            .apply { isAccessible = true }
            .set(editor, insertionHandle)
    }
    if (leftHandle != null) {
        editorClass
            .getDeclaredField("mSelectHandleLeft")
            .apply { isAccessible = true }
            .set(editor, leftHandle)
    }
    if (rightHandle != null) {
        editorClass
            .getDeclaredField("mSelectHandleRight")
            .apply { isAccessible = true }
            .set(editor, rightHandle)
    }
}

@SuppressLint("DiscouragedPrivateApi")
private fun TextView.resolveReflectedTextViewDrawable(
    textViewClass: Class<out TextView>,
    drawableFieldName: String,
    resourceFieldName: String,
): Drawable? {
    val drawableField = textViewClass.getDeclaredField(drawableFieldName).apply { isAccessible = true }
    val existing = drawableField.get(this) as? Drawable
    if (existing != null) return existing

    val resourceId =
        textViewClass.getDeclaredField(resourceFieldName).apply { isAccessible = true }.getInt(this)
    return if (resourceId != 0) context.getDrawable(resourceId) else null
}

private fun Drawable.tintedCopyPreservingState(color: Int): Drawable {
    val originalBounds = copyBounds()
    val originalHotspotBounds =
        Rect().also { hotspotBounds ->
            getHotspotBounds(hotspotBounds)
        }
    val originalState = state
    val originalLevel = level
    val originalLayoutDirection = layoutDirection
    val originalAutoMirrored = isAutoMirrored

    val copy =
        constantState?.newDrawable()?.mutate() ?: mutate()

    copy.layoutDirection = originalLayoutDirection
    copy.state = originalState
    copy.level = originalLevel
    copy.isAutoMirrored = originalAutoMirrored
    copy.setBounds(originalBounds)
    copy.setHotspotBounds(
        originalHotspotBounds.left,
        originalHotspotBounds.top,
        originalHotspotBounds.right,
        originalHotspotBounds.bottom,
    )
    copy.setTint(color)
    return copy
}
