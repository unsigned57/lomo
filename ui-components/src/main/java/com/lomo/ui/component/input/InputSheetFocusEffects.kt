package com.lomo.ui.component.input

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.lomo.ui.theme.MotionTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal const val INPUT_SHEET_EDITOR_READY_MAX_FRAMES = 12
internal const val INPUT_SHEET_FOCUS_REQUEST_MAX_ATTEMPTS = 5
internal const val INPUT_SHEET_FOCUS_SETTLE_MAX_FRAMES = 12
internal const val INPUT_SHEET_FOCUS_RELEASE_MAX_ATTEMPTS = 5
internal const val INPUT_SHEET_ENTRY_SETTLE_DELAY_MILLIS = MotionTokens.DurationLong2.toLong()
private const val INPUT_SHEET_WINDOW_FOCUS_SINK_TAG = "lomo_input_sheet_window_focus_sink"

@Composable
internal fun InputSheetVisibilityEffects(
    isSheetVisible: Boolean,
    isRecording: Boolean,
    isDismissing: Boolean,
    onSheetVisibleChange: (Boolean) -> Unit,
    onSheetEntrySettledChange: (Boolean) -> Unit,
) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        onSheetVisibleChange(true)
    }

    LaunchedEffect(isSheetVisible, isRecording, isDismissing) {
        if (!isSheetVisible || isDismissing || isRecording) {
            onSheetEntrySettledChange(false)
            return@LaunchedEffect
        }
        onSheetEntrySettledChange(false)
        delay(INPUT_SHEET_ENTRY_SETTLE_DELAY_MILLIS)
        if (!isDismissing && !isRecording && isSheetVisible) {
            onSheetEntrySettledChange(true)
        }
    }

    LaunchedEffect(isSheetVisible, isDismissing) {
        if (!isSheetVisible || isDismissing) {
            onSheetEntrySettledChange(false)
        }
    }

    LaunchedEffect(isSheetVisible, isRecording, isDismissing) {
        if (!isSheetVisible || isDismissing) return@LaunchedEffect
        if (isRecording) {
            return@LaunchedEffect
        }
    }
}

@Composable
internal fun InputSheetFocusRequestEffects(
    isSheetVisible: Boolean,
    isSheetEntrySettled: Boolean,
    isRecording: Boolean,
    isDismissing: Boolean,
    focusRequester: FocusRequester,
    focusParkingRequester: FocusRequester,
    focusRequestToken: Long,
    editorView: MemoInputEditText?,
    keyboardController: SoftwareKeyboardController?,
) {
    LaunchedEffect(isSheetVisible, isRecording, isDismissing, editorView, keyboardController) {
        if (!isSheetVisible) return@LaunchedEffect
        when {
            isDismissing -> {
                val editor = editorView ?: return@LaunchedEffect
                releaseEditorFocusAndKeyboard(
                    editor = editor,
                    keyboardController = keyboardController,
                    focusParkingRequester = focusParkingRequester,
                )
            }

            isRecording -> keyboardController?.hide()
        }
    }

    LaunchedEffect(
        isSheetVisible,
        isSheetEntrySettled,
        isRecording,
        isDismissing,
        focusRequestToken,
        editorView,
    ) {
        if (!isSheetVisible || !isSheetEntrySettled || isDismissing || isRecording) return@LaunchedEffect
        focusRequester.requestFocus()
        val editor = editorView ?: return@LaunchedEffect
        if (!awaitInputEditorReady(editor)) return@LaunchedEffect
        requestEditorFocusAndKeyboard(
            editor = editor,
            keyboardController = keyboardController,
        )
    }
}

internal fun releaseEditorFocusAndKeyboardImmediately(
    editor: MemoInputEditText?,
    keyboardController: SoftwareKeyboardController?,
    focusParkingRequester: FocusRequester,
) {
    val inputMethodManager = editor?.context?.getSystemService(InputMethodManager::class.java)
    editor?.clearFocus()
    parkInputSheetFocus(focusParkingRequester = focusParkingRequester, editor = editor)
    if (editor != null) {
        inputMethodManager?.hideSoftInputFromWindow(editor.windowToken, 0)
    }
    keyboardController?.hide()
}

private suspend fun releaseEditorFocusAndKeyboard(
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

private suspend fun awaitInputEditorReady(editor: MemoInputEditText): Boolean {
    repeat(INPUT_SHEET_EDITOR_READY_MAX_FRAMES) {
        if (editor.isAttachedToWindow && editor.isLaidOut && editor.isShown && editor.hasWindowFocus()) {
            return true
        }
        withFrameNanos { }
    }
    return editor.isAttachedToWindow && editor.isLaidOut && editor.isShown && editor.hasWindowFocus()
}

private suspend fun requestEditorFocusAndKeyboard(
    editor: MemoInputEditText,
    keyboardController: SoftwareKeyboardController?,
) {
    val inputMethodManager = editor.context.getSystemService(InputMethodManager::class.java)
    repeat(INPUT_SHEET_FOCUS_REQUEST_MAX_ATTEMPTS) {
        runOnInputEditor(editor) {
            editor.requestFocus()
            editor.requestFocusFromTouch()
            @Suppress("DEPRECATION")
            inputMethodManager?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        keyboardController?.show()
        if (awaitInputEditorFocused(editor = editor, inputMethodManager = inputMethodManager)) {
            return
        }
    }
    keyboardController?.show()
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
