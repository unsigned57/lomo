package com.lomo.app.feature.common

import android.net.Uri
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.ExitAnimationRegistry
import com.lomo.domain.model.StorageLocation
import com.lomo.app.feature.main.MemoUiModel
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
    private val exitAnimationRegistry: ExitAnimationRegistry<MemoUiModel>,
    private val errors: MemoCollectionErrors,
    private val capabilities: MemoCollectionCapabilities,
    private val scope: CoroutineScope,
    private val onMemoContentReplaced: ((Memo, String) -> Unit)?,
    private val mapToUiModel: (Memo) -> MemoUiModel,
) {
    fun delete(memo: Memo, anchoredAfterKey: String?) {
        launchAnimatedMutation(
            memo = memo,
            anchoredAfterKey = anchoredAfterKey,
            fallbackMessage = "Failed to delete memo",
        ) {
            require(capabilities !is MemoCollectionCapabilities.Trash) {
                "Cannot delete memo in Trash. Use restore or deletePermanently instead."
            }
            val deleteMemo = capabilities.deleteMemo("delete")
            deleteMemo(memo)
        }
    }

    fun updateMemo(
        memo: Memo,
        newContent: String,
    ) {
        launchMutation(fallbackMessage = "Failed to update memo") {
            val editable = capabilities.editable("update memo")
            editable.updateMemo(memo, newContent)
            onMemoContentReplaced?.invoke(memo, newContent)
        }
    }

    fun toggleTodo(
        memo: Memo,
        lineIndex: Int,
        checked: Boolean,
    ) {
        launchMutation(fallbackMessage = "Failed to update todo") {
            val toggleTodo = capabilities.toggleTodo("toggle todo")
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
        scope.launch {
            runCatching {
                val editable = capabilities.editable("save image")
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

    fun restore(memo: Memo, anchoredAfterKey: String?) {
        launchAnimatedMutation(
            memo = memo,
            anchoredAfterKey = anchoredAfterKey,
            fallbackMessage = "Failed to restore memo",
        ) {
            require(capabilities is MemoCollectionCapabilities.Trash) {
                "Cannot restore memo. Collection is not Trash."
            }
            val trash = capabilities.trash("restore memo")
            trash.restoreMemo(memo)
        }
    }

    fun deletePermanently(memo: Memo, anchoredAfterKey: String?) {
        launchAnimatedMutation(
            memo = memo,
            anchoredAfterKey = anchoredAfterKey,
            fallbackMessage = "Failed to delete memo",
        ) {
            require(capabilities is MemoCollectionCapabilities.Trash) {
                "Cannot permanently delete memo. Collection is not Trash."
            }
            val trash = capabilities.trash("delete permanently")
            trash.deletePermanently(memo)
        }
    }

    fun clearTrash(items: List<Triple<String, Memo, String?>>) {
        if (items.isEmpty()) return
        launchAnimatedMutationBulk(
            items = items,
            fallbackMessage = "Failed to clear trash",
        ) {
            require(capabilities is MemoCollectionCapabilities.Trash) {
                "Cannot clear trash. Collection is not Trash."
            }
            val trash = capabilities.trash("clear trash")
            trash.clearTrash()
        }
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
        memo: Memo,
        anchoredAfterKey: String?,
        fallbackMessage: String,
        mutation: suspend () -> Unit,
    ) {
        scope.launch {
            runCatching {
                val uiModel = mapToUiModel(memo)
                runDeleteAnimationWithRollback(
                    itemId = memo.id,
                    registry = exitAnimationRegistry,
                    item = uiModel,
                    anchoredAfterKey = anchoredAfterKey,
                    mutation = mutation,
                )
            }.onFailure { throwable ->
                errors.report(throwable, fallbackMessage)
            }
        }
    }

    private fun launchAnimatedMutationBulk(
        items: List<Triple<String, Memo, String?>>,
        fallbackMessage: String,
        mutation: suspend () -> Unit,
    ) {
        scope.launch {
            runCatching {
                val mappedItems = items.map { (id, memo, anchor) ->
                    Triple(id, mapToUiModel(memo), anchor)
                }
                runDeleteAnimationWithRollback(
                    items = mappedItems,
                    registry = exitAnimationRegistry,
                    mutation = mutation,
                )
            }.onFailure { throwable ->
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
