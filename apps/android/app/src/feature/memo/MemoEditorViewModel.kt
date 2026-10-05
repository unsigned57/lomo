package com.lomo.app.feature.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.util.runSuspendCatching
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.RecoverableDraftFailure
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DiscardDraftMediaUseCase
import com.lomo.domain.usecase.LoadCreateDraftUseCase
import com.lomo.domain.usecase.ReconcileDraftMediaUseCase
import com.lomo.domain.usecase.SaveCreateDraftUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


class MemoEditorViewModel(
    createMemoUseCase: CreateMemoUseCase,
    updateMemoContentUseCase: UpdateMemoContentUseCase,
    private val saveImageUseCase: SaveImageUseCase,
    private val discardDraftMediaUseCase: DiscardDraftMediaUseCase,
    private val loadCreateDraftUseCase: LoadCreateDraftUseCase,
    private val saveCreateDraftUseCase: SaveCreateDraftUseCase,
    private val reconcileDraftMediaUseCase: ReconcileDraftMediaUseCase,
    private val diagnostics: EngineDiagnosticsRecorder,
) : ViewModel() {
    /** Staged draft media destinations (image + voice relative paths) for discard. */
    // behavior-contract: mutable-payload-ok: private discard ledger for staged-media cleanup; never rendered or exposed
    private val trackedStagedMedia = mutableSetOf<String>()

    /**
     * This editor's durable draft identity — the lease owner of every staged media item. It is
     * minted at construction and adopted from the persisted create draft on recovery, so staged
     * media survives process death under the same owner.
     */
    private val draftId = MutableStateFlow(DraftId.mint())
    private val hasLocalDraftMutation = MutableStateFlow(false)

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText
    internal val submissions =
        MemoEditorCommitCoordinator(
            draftId = { draftId.value },
            scope = viewModelScope,
            createMemo = { attempt -> createMemoUseCase(attempt) },
            updateMemo = updateMemoContentUseCase::invoke,
            onCreateCommitted = {
                clearTrackedStagedMedia()
                clearDraft()
            },
            onUpdateCommitted = {
                clearTrackedStagedMedia()
            },
            onStarted = { _errorMessage.value = null },
            onFailure = { throwable -> _errorMessage.value = throwable.toUserMessage() },
        )
    val submissionState: StateFlow<MemoEditorSubmissionState> = submissions.state
    val editorScreenState: StateFlow<MemoEditorScreenState> =
        combine(draftText, errorMessage, submissionState) { text, error, submission ->
            MemoEditorScreenState(
                draftText = text,
                errorMessage = error,
                submission = submission,
            )
        }.stateIn(
            viewModelScope,
            appWhileSubscribed(),
            MemoEditorScreenState(
                draftText = _draftText.value,
                errorMessage = _errorMessage.value,
                submission = submissions.state.value,
            ),
        )

    init {
        viewModelScope.launch {
            val persistedDraft = loadCreateDraftUseCase()
            if (!hasLocalDraftMutation.value) {
                _draftText.value = persistedDraft?.content.orEmpty()
                // Adopt the recovered draft's lease identity so media staged before process death
                // is still owned by this session, then reconcile it against the stage ledger.
                persistedDraft?.let { draft ->
                    draftId.value = draft.draftId
                    try {
                        reconcileDraftMediaUseCase(draft.draftId)
                    } catch (recoverable: RecoverableDraftFailure) {
                        _errorMessage.value = recoverable.toUserMessage()
                    }
                }
            }
        }
        observeStalledSubmissions()
    }

    /**
     * Publishes the one symptom that throws nothing: a submission that never reaches a terminal
     * state keeps the editor open forever, so the elapsed budget itself has to become observable.
     */
    private fun observeStalledSubmissions() {
        viewModelScope.launch {
            submissions.state.collectLatest { state ->
                if (state is MemoEditorSubmissionState.Submitting) {
                    delay(SUBMISSION_STALL_THRESHOLD_MILLIS)
                    diagnostics.record(
                        EngineDiagnosticEvent.Stalled(
                            label = "editor.submit",
                            durationMillis = SUBMISSION_STALL_THRESHOLD_MILLIS,
                        ),
                    )
                }
            }
        }
    }

        private var draftJob: kotlinx.coroutines.Job? = null

        fun saveDraft(text: String) {
            hasLocalDraftMutation.value = true
            _draftText.value = text
            draftJob?.cancel()
            draftJob = viewModelScope.launch {
                saveCreateDraftUseCase(draftId.value, text)
            }
        }

    fun clearDraft() {
            hasLocalDraftMutation.value = true
            _draftText.value = ""
            draftJob?.cancel()
            draftJob = viewModelScope.launch {
                saveCreateDraftUseCase(draftId.value, null)
            }
        }

        /**
         * Stages an image under the lease of [draftId] — the effective draft the caller resolved
         * (an open edit session's durable draft, else this editor's own create draft).
         */
        fun saveImage(
            uri: android.net.Uri,
            draftId: DraftId,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                runSuspendCatching {
                    val path =
                        saveImageUseCase.saveWithCacheSyncStatus(
                            StorageLocation(uri.toString()),
                            draftId,
                        ).location.raw
                    trackStagedMedia(path)
                    onResult(path)
                }.onFailure { throwable ->
                    _errorMessage.value = throwable.toUserMessage("Failed to save image")
                    onError?.invoke()
                }
            }
        }

        /**
         * Records a staged media destination (image or voice) so draft discard can drop the stage.
         * Call with the markdown destination path (e.g. `media/voice_….m4a`), not the full markdown.
         */
        fun trackStagedMedia(destination: String) {
            val key = destination.trim()
            if (key.isNotEmpty()) {
                trackedStagedMedia += key
            }
        }

        /**
         * Tracks voice markdown inserted after finalize (`![voice](dest)`). Extracts dest only.
         */
        fun trackVoiceMarkdown(markdown: String) {
            val dest = extractMarkdownDestination(markdown) ?: return
            trackStagedMedia(dest)
        }

        /**
         * Discards the media staged under [draftId] — the effective draft that was dismissed —
         * then releases every lease that draft still owns.
         */
        fun discardInputs(draftId: DraftId) {
            viewModelScope.launch {
                runSuspendCatching {
                    val toDelete = trackedStagedMedia.toList()
                    trackedStagedMedia.clear()
                    discardDraftMediaUseCase(toDelete, draftId)
                }.onFailure { throwable ->
                    _errorMessage.value = throwable.toUserMessage("Failed to discard input")
                }
            }
        }

        /** The draft identity this editor's media leases belong to. */
        internal val ownerDraftId: DraftId
            get() = draftId.value

        /** Surfaces a non-mutation failure (e.g. editor open) on the shared error channel. */
        fun reportError(throwable: Throwable) {
            _errorMessage.value = throwable.toUserMessage("Failed to open memo")
        }

        fun clearError() {
            _errorMessage.value = null
        }

    private fun clearTrackedStagedMedia() {
        trackedStagedMedia.clear()
    }

    companion object {
            /** A submission still in flight past this budget is reported as stalled. */
            private const val SUBMISSION_STALL_THRESHOLD_MILLIS = 8_000L

            /** Best-effort `![alt](dest)` / `[alt](dest)` destination extract for draft tracking. */
            internal fun extractMarkdownDestination(markdown: String): String? {
                val open = markdown.indexOf('(')
                val close = markdown.lastIndexOf(')')
                if (open < 0 || close <= open) return null
                return markdown
                    .substring(open + 1, close)
                    .trim()
                    .trim('<', '>')
                    .takeIf { it.isNotEmpty() }
            }
    }
}

data class MemoEditorScreenState(
    val draftText: String,
    val errorMessage: String?,
    val submission: MemoEditorSubmissionState,
)
