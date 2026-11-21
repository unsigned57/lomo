package com.lomo.app.feature.common

import android.net.Uri
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoEditSession
import com.lomo.app.feature.memo.MemoEditorAttemptStore
import com.lomo.app.feature.memo.MemoEditorSubmissionId
import com.lomo.app.feature.memo.MemoEditorSubmissionState
import com.lomo.app.feature.memo.MemoEditorSubmissionStateMachine
import com.lomo.app.util.runSuspendCatching
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.EditableMemoSnapshot
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.model.MemoUpdateAttempt
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.ui.component.common.ExitAnimationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface MemoCollectionCapabilities {
    data class DeletableTodo(
        val deleteMemo: suspend (Memo, MemoOperationId) -> Unit,
        val toggleTodo: suspend (Memo, MarkdownSourceSpan) -> String,
    ) : MemoCollectionCapabilities

    data class Editable(
        val deleteMemo: suspend (Memo, MemoOperationId) -> Unit,
        val updateMemo: suspend (MemoUpdateAttempt) -> Unit,
        val toggleTodo: suspend (Memo, MarkdownSourceSpan) -> String,
        val saveImage: suspend (StorageLocation, DraftId) -> SaveImageResult,
        /**
         * Re-reads the authoritative full snapshot for an edit baseline. A row `Memo` is never
         * trusted — it may be a bounded preview — so every update binds only through this read.
         */
        val loadEditableMemo: suspend (String) -> EditableMemoSnapshot?,
    ) : MemoCollectionCapabilities

    data class Trash(
        val restoreMemo: suspend (Memo, MemoOperationId) -> Unit,
        val deletePermanently: suspend (Memo, MemoOperationId) -> Unit,
        val clearTrash: suspend (MemoOperationId) -> Unit,
    ) : MemoCollectionCapabilities
}

class MemoCollectionActions internal constructor(
    private val exitAnimationRegistry: ExitAnimationRegistry<MemoUiModel>,
    private val errors: MemoCollectionErrors,
    /** This surface scope's media lease owner identity. */
    val draftId: DraftId,
    private val editorSubmissionStateMachine: MemoEditorSubmissionStateMachine =
        MemoEditorSubmissionStateMachine(),
    private val capabilities: MemoCollectionCapabilities,
    private val scope: CoroutineScope,
    private val mapToUiModel: suspend (Memo) -> MemoUiModel,
) {
    private val pendingAnimatedMutations = mutableSetOf<String>()
    private val editorAttempts = MemoEditorAttemptStore({ draftId })
    fun delete(memo: Memo, anchoredAfterKey: String?) {
        val operationId = newMemoOperationId()
        launchAnimatedMutation(
            memo = memo,
            anchoredAfterKey = anchoredAfterKey,
            fallbackMessage = "Failed to delete memo",
        ) {
            require(capabilities !is MemoCollectionCapabilities.Trash) {
                "Cannot delete memo in Trash. Use restore or deletePermanently instead."
            }
            val deleteMemo = capabilities.deleteMemo("delete")
            deleteMemo(memo, operationId)
        }
    }

    /**
     * Direct list-row update. The row `Memo` carries no verified baseline — it may be a bounded
     * preview — so the authoritative full snapshot is re-read before the attempt is frozen.
     * A memo whose snapshot cannot be verified is rejected, not edited.
     */
    fun updateMemo(memo: Memo, newContent: String) {
        launchMutation(fallbackMessage = "Failed to update memo") {
            val editable = capabilities.editable("update memo")
            val snapshot =
                editable.loadEditableMemo(memo.id)
                    ?: error("Cannot edit a memo whose full snapshot cannot be verified")
            editable.updateMemo(
                editorAttempts.update(
                    MemoEditSession(snapshot = snapshot, draftId = draftId),
                    newContent,
                    MemoEditorSubmissionState.Idle,
                ),
            )
        }
    }

    suspend fun submitMemoUpdate(
        submissionId: MemoEditorSubmissionId,
        session: MemoEditSession,
        newContent: String,
    ): Boolean {
        editorSubmissionStateMachine.launch(
            scope = scope,
            submissionId = submissionId,
            onFailure = { throwable -> errors.report(throwable, "Failed to update memo") },
        ) { previous ->
            val editable = capabilities.editable("update memo")
            editable.updateMemo(editorAttempts.update(session, newContent, previous))
        }
        return editorSubmissionStateMachine.await(submissionId)
    }

    fun toggleTodo(memo: Memo, actionSpan: MarkdownSourceSpan) {
        launchMutation(fallbackMessage = "Failed to update todo") {
            capabilities.toggleTodo("toggle todo")(memo, actionSpan)
        }
    }

    /**
     * Stages multiple images under the lease of [draftId] in order — the effective draft the caller resolved
     * (an open edit session's durable draft, else this surface's own).
     */
    fun saveImages(
        uris: List<Uri>,
        draftId: DraftId,
        onResult: (List<String>) -> Unit,
        onError: (() -> Unit)? = null,
    ) {
        if (uris.isEmpty()) return
        scope.launch {
            val savedPaths = mutableListOf<String>()
            runSuspendCatching {
                val editable = capabilities.editable("save image")
                for (uri in uris) {
                    val path = editable.saveImage(StorageLocation(uri.toString()), draftId).location.raw
                    savedPaths += path
                }
                onResult(savedPaths)
            }.onFailure { throwable ->
                if (savedPaths.isNotEmpty()) {
                    onResult(savedPaths)
                }
                errors.report(throwable, "Failed to save image")
                onError?.invoke()
            }
        }
    }

    /**
     * Restores one trashed memo. [onCommitted] runs only after the durable restore commits, so a
     * caller that re-presents the memo (e.g. focusing it back in the main feed) is never armed by
     * a mutation that later rolls back. The observer must be an infallible signal — a throwing
     * observer would misreport a committed restore as failed.
     */
    fun restore(memo: Memo, anchoredAfterKey: String?, onCommitted: (Memo) -> Unit = {}) {
        val operationId = newMemoOperationId()
        launchAnimatedMutation(
            memo = memo,
            anchoredAfterKey = anchoredAfterKey,
            fallbackMessage = "Failed to restore memo",
            onCommitted = { onCommitted(memo) },
        ) {
            require(capabilities is MemoCollectionCapabilities.Trash) {
                "Cannot restore memo. Collection is not Trash."
            }
            val trash = capabilities.trash("restore memo")
            trash.restoreMemo(memo, operationId)
        }
    }

    fun deletePermanently(memo: Memo, anchoredAfterKey: String?) {
        val operationId = newMemoOperationId()
        launchAnimatedMutation(
            memo = memo,
            anchoredAfterKey = anchoredAfterKey,
            fallbackMessage = "Failed to delete memo",
        ) {
            require(capabilities is MemoCollectionCapabilities.Trash) {
                "Cannot permanently delete memo. Collection is not Trash."
            }
            val trash = capabilities.trash("delete permanently")
            trash.deletePermanently(memo, operationId)
        }
    }

    fun clearTrash() {
        val operationId = newMemoOperationId()
        launchMutation(fallbackMessage = "Failed to clear trash") {
            capabilities.trash("clear trash").clearTrash(operationId)
        }
    }

    private fun launchMutation(fallbackMessage: String, mutation: suspend () -> Unit) {
        scope.launch {
            runSuspendCatching {
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
        onCommitted: () -> Unit = {},
        mutation: suspend () -> Unit,
    ) {
        if (memo.id in exitAnimationRegistry.entries.value || !pendingAnimatedMutations.add(memo.id)) return
        scope.launch {
            try {
                runSuspendCatching {
                    val uiModel = mapToUiModel(memo)
                    runDeleteAnimationWithRollback(
                        itemId = memo.id,
                        registry = exitAnimationRegistry,
                        item = uiModel,
                        anchoredAfterKey = anchoredAfterKey,
                        mutation = mutation,
                    )
                    onCommitted()
                }.onFailure { throwable ->
                    errors.report(throwable, fallbackMessage)
                }
            } finally {
                pendingAnimatedMutations.remove(memo.id)
            }
        }
    }

    private companion object {
        private fun MemoCollectionCapabilities.deleteMemo(action: String): suspend (Memo, MemoOperationId) -> Unit =
            when (this) {
                is MemoCollectionCapabilities.DeletableTodo -> deleteMemo
                is MemoCollectionCapabilities.Editable -> deleteMemo
                is MemoCollectionCapabilities.Trash -> error("Memo collection capability does not support $action")
            }

        private fun MemoCollectionCapabilities.toggleTodo(
            action: String,
        ): suspend (Memo, MarkdownSourceSpan) -> String = when (this) {
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
    private val diagnostics: EngineDiagnosticsRecorder,
) {
    fun clear() {
        errorMessage.value = null
    }

    fun report(throwable: Throwable, fallbackMessage: String) {
        if (throwable is CancellationException) {
            throw throwable
        }
        // The user surface only gets the sanitized message; logcat is the diagnostics channel
        // for the real failure class and any structured engine code it carries.
        timber.log.Timber.w(throwable, "Memo collection action failed: %s", fallbackMessage)
        errorMessage.value = diagnostics.reportUserFailure("memo.collection", throwable, fallbackMessage)
    }
}

/**
 * Convenience entry to stage a single image under the lease of [draftId].
 */
fun MemoCollectionActions.saveImage(
    uri: Uri,
    draftId: DraftId,
    onResult: (String) -> Unit,
    onError: (() -> Unit)? = null,
) {
    saveImages(
        uris = listOf(uri),
        draftId = draftId,
        onResult = { paths -> paths.firstOrNull()?.let(onResult) },
        onError = onError,
    )
}
