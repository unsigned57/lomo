package com.lomo.ui.component.input

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val INPUT_SHEET_WINDOW_FOCUS_SINK_TAG = "lomo_input_sheet_window_focus_sink"

internal suspend fun releaseEditorFocusAndKeyboard(
    editor: MemoInputEditText,
    keyboardController: SoftwareKeyboardController?,
    focusParkingRequester: FocusRequester,
) {
    val inputMethodManager = editor.context.getSystemService(InputMethodManager::class.java)
    repeat(INPUT_SHEET_FOCUS_RELEASE_MAX_ATTEMPTS) {
        runOnInputEditor(editor) {
            editor.clearFocus()
            inputMethodManager?.hideSoftInputFromWindow(editor.windowToken, 0)
        }
        parkInputSheetFocus(focusParkingRequester = focusParkingRequester, editor = editor)
        keyboardController?.hide()
        if (awaitInputEditorBlurred(editor = editor, inputMethodManager = inputMethodManager)) {
            return
        }
    }
    keyboardController?.hide()
}

internal fun releaseEditorWindowFocus(
    editor: MemoInputEditText?,
    focusParkingRequester: FocusRequester,
) {
    val inputMethodManager = editor?.context?.getSystemService(InputMethodManager::class.java)
    parkInputSheetFocus(focusParkingRequester = focusParkingRequester, editor = editor)
    if (editor != null) {
        inputMethodManager?.hideSoftInputFromWindow(editor.windowToken, 0)
    }
}

internal suspend fun awaitInputEditorReady(editor: MemoInputEditText): Boolean {
    repeat(INPUT_SHEET_EDITOR_READY_MAX_FRAMES) {
        if (editor.isAttachedToWindow && editor.isLaidOut && editor.isShown && editor.hasWindowFocus()) {
            return true
        }
        withFrameNanos { }
    }
    return editor.isAttachedToWindow && editor.isLaidOut && editor.isShown && editor.hasWindowFocus()
}

@SuppressLint("Deprecated")
internal suspend fun requestEditorFocusAndKeyboard(
    editor: MemoInputEditText,
    keyboardController: SoftwareKeyboardController?,
) {
    val inputMethodManager = editor.context.getSystemService(InputMethodManager::class.java)
    repeat(INPUT_SHEET_FOCUS_REQUEST_MAX_ATTEMPTS) {
        runOnInputEditor(editor) {
            editor.requestFocus()
            editor.requestFocusFromTouch()
            inputMethodManager?.showSoftInput(editor, 0)
        }
        keyboardController?.show()
        if (awaitInputEditorFocused(editor = editor, inputMethodManager = inputMethodManager)) {
            return
        }
    }
    keyboardController?.show()
}

private fun parkInputSheetFocus(
    focusParkingRequester: FocusRequester,
    editor: MemoInputEditText? = null,
) {
    parkWindowRootFocus(editor)
    runCatching { focusParkingRequester.requestFocus() }
}

private fun parkWindowRootFocus(editor: MemoInputEditText?) {
    val rootView = editor?.rootView ?: return
    val focusHost =
        rootView.findViewById<ViewGroup?>(android.R.id.content) ?: (rootView as? ViewGroup)
    val persistentFocusTarget =
        focusHost?.findViewWithTag<View>(INPUT_SHEET_WINDOW_FOCUS_SINK_TAG)
            ?: focusHost?.let { contentRoot ->
                View(contentRoot.context).apply {
                    tag = INPUT_SHEET_WINDOW_FOCUS_SINK_TAG
                    layoutParams = ViewGroup.LayoutParams(1, 1)
                    alpha = 0f
                    isClickable = false
                    isFocusable = true
                    isFocusableInTouchMode = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    contentRoot.addView(this)
                }
            }
            ?: rootView
    if (!persistentFocusTarget.isFocusable) persistentFocusTarget.isFocusable = true
    if (!persistentFocusTarget.isFocusableInTouchMode) {
        persistentFocusTarget.isFocusableInTouchMode = true
    }
    persistentFocusTarget.requestFocus()
}

private suspend fun awaitInputEditorFocused(
    editor: MemoInputEditText,
    inputMethodManager: InputMethodManager?,
): Boolean {
    repeat(INPUT_SHEET_FOCUS_SETTLE_MAX_FRAMES) {
        if (editor.hasFocus() && inputMethodManager?.isActive(editor) == true) {
            return true
        }
        withFrameNanos { }
    }
    return editor.hasFocus() && inputMethodManager?.isActive(editor) == true
}

private suspend fun awaitInputEditorBlurred(
    editor: MemoInputEditText,
    inputMethodManager: InputMethodManager?,
): Boolean {
    repeat(INPUT_SHEET_FOCUS_SETTLE_MAX_FRAMES) {
        if (!editor.hasFocus() && inputMethodManager?.isActive(editor) != true) {
            return true
        }
        withFrameNanos { }
    }
    return !editor.hasFocus() && inputMethodManager?.isActive(editor) != true
}

private suspend fun runOnInputEditor(
    editor: MemoInputEditText,
    action: () -> Unit,
) {
    suspendCancellableCoroutine { continuation ->
        editor.post {
            action()
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
    }
}
