package com.lomo.app.feature.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DiscardDraftMediaUseCase
import com.lomo.domain.usecase.ObserveDraftTextUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.SetDraftTextUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


class MemoEditorViewModel(
    private val createMemoUseCase: CreateMemoUseCase,
    private val updateMemoContentUseCase: UpdateMemoContentUseCase,
    private val saveImageUseCase: SaveImageUseCase,
    private val discardDraftMediaUseCase: DiscardDraftMediaUseCase,
    private val appWidgetRepository: AppWidgetRepository,
    private val observeDraftTextUseCase: ObserveDraftTextUseCase,
    private val setDraftTextUseCase: SetDraftTextUseCase,
) : ViewModel() {
        /** Staged draft media destinations (image + voice relative paths) for discard. */
        private val trackedStagedMedia = mutableSetOf<String>()
        private var hasLocalDraftMutation = false

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        private val _draftText = MutableStateFlow("")
        val draftText: StateFlow<String> = _draftText

        init {
            viewModelScope.launch {
                val persistedDraft = observeDraftTextUseCase().first()
                if (!hasLocalDraftMutation) {
                    _draftText.value = persistedDraft
                }
            }
        }

        fun saveDraft(text: String) {
            hasLocalDraftMutation = true
            _draftText.value = text
            viewModelScope.launch {
                setDraftTextUseCase(text)
            }
        }

        fun clearDraft() {
            hasLocalDraftMutation = true
            _draftText.value = ""
            viewModelScope.launch {
                setDraftTextUseCase(null)
            }
        }

        fun createMemo(
            content: String,
            onSuccess: (() -> Unit)? = null,
            geoLocation: String? = null,
            timestampMillis: Long? = null,
        ) {
            viewModelScope.launch {
                runCatching {
                    createMemoUseCase(
                        content = content,
                        timestampMillis = timestampMillis ?: System.currentTimeMillis(),
                        geoLocation = geoLocation,
                    )
                    onSuccess?.invoke()
                    clearTrackedStagedMedia()
                    clearDraft()
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { appWidgetRepository.updateAllWidgets() }
                    }
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage()
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                runCatching {
                    updateMemoContentUseCase(memo, newContent)
                    appWidgetRepository.updateAllWidgets()
                    clearTrackedStagedMedia()
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _errorMessage.value = throwable.toUserMessage()
                }
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

        companion object {
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
