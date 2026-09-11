package com.lomo.app.feature.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DiscardDraftMediaUseCase
import com.lomo.domain.usecase.ObserveDraftTextUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.SetDraftTextUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


class MemoEditorViewModel(
    private val createMemoUseCase: CreateMemoUseCase,
    private val updateMemoContentUseCase: UpdateMemoContentUseCase,
    private val saveImageUseCase: SaveImageUseCase,
    private val discardDraftMediaUseCase: DiscardDraftMediaUseCase,
    private val appWidgetRepository: AppWidgetRepository,
    private val observeDraftTextUseCase: ObserveDraftTextUseCase,
    private val setDraftTextUseCase: SetDraftTextUseCase,
    private val diagnostics: EngineDiagnosticsRecorder,
) : ViewModel() {
    /** Staged draft media destinations (image + voice relative paths) for discard. */
    private val trackedStagedMedia = mutableSetOf<String>()
    private val hasLocalDraftMutation = MutableStateFlow(false)

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText
    internal val submissions =
        MemoEditorCommitCoordinator(
            scope = viewModelScope,
            createMemo = { content, geoLocation, timestampMillis ->
                createMemoUseCase(
                    content = content,
                    timestampMillis = timestampMillis ?: System.currentTimeMillis(),
                    geoLocation = geoLocation,
                )
            },
            updateMemo = updateMemoContentUseCase::invoke,
            onCreateCommitted = {
                clearTrackedStagedMedia()
                clearDraft()
                refreshWidgetsAfterCommit()
            },
            onUpdateCommitted = {
                clearTrackedStagedMedia()
                refreshWidgetsAfterCommit()
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
            val persistedDraft = observeDraftTextUseCase().first()
            if (!hasLocalDraftMutation.value) {
                _draftText.value = persistedDraft
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
                setDraftTextUseCase(text)
            }
        }

    fun clearDraft() {
            hasLocalDraftMutation.value = true
            _draftText.value = ""
            draftJob?.cancel()
            draftJob = viewModelScope.launch {
                setDraftTextUseCase(null)
            }
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                runCatching {
                    val path =
                        when (
                            val result =
                                saveImageUseCase.saveWithCacheSyncStatus(
                                    StorageLocation(uri.toString()),
                                )
                        ) {
                            is SaveImageResult.SavedAndCacheSynced -> result.location.raw
                            is SaveImageResult.SavedButCacheSyncFailed -> throw result.cause
                        }
                    trackStagedMedia(path)
                    onResult(path)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
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

        fun discardInputs() {
            viewModelScope.launch {
                runCatching {
                    val toDelete = trackedStagedMedia.toList()
                    trackedStagedMedia.clear()
                    discardDraftMediaUseCase(toDelete)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage("Failed to discard input")
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }

    private fun clearTrackedStagedMedia() {
        trackedStagedMedia.clear()
    }

    private fun refreshWidgetsAfterCommit() {
        viewModelScope.launch(Dispatchers.IO) {
            // behavior-contract: silent-result-ok: widgets are a rebuildable projection and must
            // never turn an already-durable memo commit into an editor submission failure.
            runCatching { appWidgetRepository.updateAllWidgets() }
        }
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
