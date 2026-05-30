package com.lomo.app.feature.common

import android.net.Uri
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.SaveImageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface MemoCollectionCapabilities {
    data class DeletableTodo(
        val deleteMemo: suspend (Memo) -> Unit,
        val toggleTodo: suspend (Memo, Int, Boolean) -> String?,
    ) : MemoCollectionCapabilities

    data class Editable(
        val deleteMemo: suspend (Memo) -> Unit,
        val updateMemo: suspend (Memo, String) -> Unit,
        val toggleTodo: suspend (Memo, Int, Boolean) -> String?,
        val saveImage: suspend (StorageLocation) -> SaveImageResult,
    ) : MemoCollectionCapabilities

    data class Trash(
        val restoreMemo: suspend (Memo) -> Unit,
        val deletePermanently: suspend (Memo) -> Unit,
        val clearTrash: suspend () -> Unit,
    ) : MemoCollectionCapabilities
}

class MemoCollectionActions internal constructor(
    private val trashSnapshot: (() -> List<Memo>)?,
    private val deletingMemoIds: MutableStateFlow<Set<String>>,
    private val errors: MemoCollectionErrors,
    private val capabilities: MemoCollectionCapabilities,
    private val scope: CoroutineScope,
    private val onMemoContentReplaced: ((Memo, String) -> Unit)?,
) {
    fun delete(memo: Memo) {
        val deleteMemo = capabilities.deleteMemo("delete")
        launchAnimatedMutation(
            itemIds = setOf(memo.id),
            fallbackMessage = "Failed to delete memo",
        ) {
            deleteMemo(memo)
        }
    }

    fun updateMemo(
        memo: Memo,
        newContent: String,
    ) {
        val editable = capabilities.editable("update memo")
        launchMutation(fallbackMessage = "Failed to update memo") {
            editable.updateMemo(memo, newContent)
            onMemoContentReplaced?.invoke(memo, newContent)
        }
    }

    fun toggleTodo(
        memo: Memo,
        lineIndex: Int,
        checked: Boolean,
    ) {
        val toggleTodo = capabilities.toggleTodo("toggle todo")
        launchMutation(fallbackMessage = "Failed to update todo") {
            val newContent = toggleTodo(memo, lineIndex, checked)
            if (newContent != null) {
                onMemoContentReplaced?.invoke(memo, newContent)
            }
        }
    }

    fun saveImage(
        uri: Uri,
        onResult: (String) -> Unit,
        onError: (() -> Unit)? = null,
    ) {
        val editable = capabilities.editable("save image")
        scope.launch {
            runCatching {
                val path =
                    when (val result = editable.saveImage(StorageLocation(uri.toString()))) {
                        is SaveImageResult.SavedAndCacheSynced -> result.location.raw
                        is SaveImageResult.SavedButCacheSyncFailed -> throw result.cause
                    }
                onResult(path)
            }.onFailure { throwable ->
                errors.report(throwable, "Failed to save image")
                onError?.invoke()
            }
        }
    }

    fun restore(memo: Memo) {
        val trash = capabilities.trash("restore memo")
        launchAnimatedMutation(
            itemIds = setOf(memo.id),
            fallbackMessage = "Failed to restore memo",
        ) {
            trash.restoreMemo(memo)
        }
    }

    fun deletePermanently(memo: Memo) {
        val trash = capabilities.trash("delete permanently")
        launchAnimatedMutation(
            itemIds = setOf(memo.id),
            fallbackMessage = "Failed to delete memo",
        ) {
            trash.deletePermanently(memo)
        }
    }

    fun clearTrash() {
        val trash = capabilities.trash("clear trash")
        val trashSnapshot = requireNotNull(trashSnapshot) {
            "Trash collection actions require a collection snapshot"
        }.invoke()
        if (trashSnapshot.isEmpty()) {
            return
        }
        launchAnimatedMutation(
            itemIds = trashSnapshot.asSequence().map(Memo::id).toSet(),
            fallbackMessage = "Failed to clear trash",
        ) {
            trash.clearTrash()
        }
    }

    fun onDeleteAnimationSettled(memoId: String) {
        deletingMemoIds.update { ids -> ids - memoId }
    }

    private fun launchMutation(
        fallbackMessage: String,
        mutation: suspend () -> Unit,
    ) {
        scope.launch {
            runCatching {
                mutation()
            }.onFailure { throwable ->
                errors.report(throwable, fallbackMessage)
            }
        }
    }

    private fun launchAnimatedMutation(
        itemIds: Set<String>,
        fallbackMessage: String,
        mutation: suspend () -> Unit,
    ) {
        scope.launch {
            val result =
                runDeleteAnimationWithRollback(
                    itemIds = itemIds,
                    deletingIds = deletingMemoIds,
                    mutation = mutation,
                )
            result.exceptionOrNull()?.let { throwable ->
                errors.report(throwable, fallbackMessage)
            }
        }
    }

    private companion object {
        private fun MemoCollectionCapabilities.deleteMemo(action: String): suspend (Memo) -> Unit =
            when (this) {
                is MemoCollectionCapabilities.DeletableTodo -> deleteMemo
                is MemoCollectionCapabilities.Editable -> deleteMemo
                is MemoCollectionCapabilities.Trash -> error("Memo collection capability does not support $action")
            }

        private fun MemoCollectionCapabilities.toggleTodo(action: String): suspend (Memo, Int, Boolean) -> String? =
            when (this) {
                is MemoCollectionCapabilities.DeletableTodo -> toggleTodo
                is MemoCollectionCapabilities.Editable -> toggleTodo
                is MemoCollectionCapabilities.Trash -> error("Memo collection capability does not support $action")
            }

        private fun MemoCollectionCapabilities.editable(action: String): MemoCollectionCapabilities.Editable =
            this as? MemoCollectionCapabilities.Editable
                ?: error("Memo collection capability does not support $action")

        private fun MemoCollectionCapabilities.trash(action: String): MemoCollectionCapabilities.Trash =
            this as? MemoCollectionCapabilities.Trash
                ?: error("Memo collection capability does not support $action")
    }
}

class MemoCollectionErrors internal constructor(
    private val errorMessage: MutableStateFlow<String?>,
) {
    fun clear() {
        errorMessage.value = null
    }

    fun report(
        throwable: Throwable,
        fallbackMessage: String,
    ) {
        if (throwable is CancellationException) {
            throw throwable
        }
        errorMessage.value = throwable.toUserMessage(fallbackMessage)
    }
}
