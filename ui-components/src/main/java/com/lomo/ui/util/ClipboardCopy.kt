package com.lomo.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Writes [text] into the system clipboard on [Dispatchers.IO] so the synchronous Binder IPC
 * to the clipboard service does not occupy the frame that initiated the copy.
 *
 * Returns immediately on the calling thread. The android system clipboard preview overlay
 * (Android 13+) is owned by the platform and shows itself after the clip is committed; the
 * caller is responsible for dismissing any local UI (action sheet, selection chrome) before
 * or concurrently with this call so the user perceives instant feedback rather than waiting
 * for the IPC and the system overlay to settle in sequence.
 */
fun ClipboardManager.copyPlainTextAsync(
    scope: CoroutineScope,
    label: String,
    text: String,
) {
    scope.launch(Dispatchers.IO) {
        setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
