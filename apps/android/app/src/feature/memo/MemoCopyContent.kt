package com.lomo.app.feature.memo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lomo.domain.model.MemoContentKind
import com.lomo.domain.repository.MainListQueryRepository
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Resolves a complete current body before a menu copy can reach the clipboard. */
internal suspend fun loadMemoContentForCopy(selection: MemoMenuSelection, repository: MainListQueryRepository): String {
    val memoId = selection.memo.id
    val memo = checkNotNull(repository.getMemoById(memoId)) { "Memo is no longer available" }
    check(
        memo.id == memoId &&
            memo.contentKind == MemoContentKind.Full &&
            memo.projectedCharCount == memo.rawContent.length.toLong(),
    ) { "Memo copy requires a complete snapshot" }
    return memo.content
}

internal suspend fun copyMemoToClipboard(
    context: Context,
    repository: MainListQueryRepository,
    selection: MemoMenuSelection,
    label: String,
    failureMessage: String,
    dispatcherProvider: DispatcherProvider,
) {
    try {
        val content = loadMemoContentForCopy(selection, repository)
        val clipboard = checkNotNull(ContextCompat.getSystemService(context, ClipboardManager::class.java)) {
            "Clipboard service is unavailable"
        }
        withContext(dispatcherProvider.io) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Timber.w(error, "Failed to copy complete memo")
        Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
    }
}
