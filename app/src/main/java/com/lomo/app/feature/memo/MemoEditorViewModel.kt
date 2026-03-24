package com.lomo.app.feature.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DiscardMemoDraftAttachmentsUseCase
import com.lomo.domain.usecase.ObserveDraftTextUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.SetDraftTextUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltViewModel
class MemoEditorViewModel
    @Inject
    constructor(
        private val createMemoUseCase: CreateMemoUseCase,
        private val updateMemoContentUseCase: UpdateMemoContentUseCase,
        private val saveImageUseCase: SaveImageUseCase,
        private val discardMemoDraftAttachmentsUseCase: DiscardMemoDraftAttachmentsUseCase,
        private val appWidgetRepository: AppWidgetRepository,
        private val observeDraftTextUseCase: ObserveDraftTextUseCase,
        private val setDraftTextUseCase: SetDraftTextUseCase,
    ) : ViewModel() {
        private val trackedImageFilenames = mutableSetOf<String>()

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        private val _draftText = MutableStateFlow(
            runBlocking { observeDraftTextUseCase().first() },
        )
        val draftText: StateFlow<String> = _draftText

        fun saveDraft(text: String) {
            _draftText.value = text
            viewModelScope.launch {
                setDraftTextUseCase(text)
            }
        }

        fun clearDraft() {
            _draftText.value = ""
            viewModelScope.launch {
                setDraftTextUseCase(null)
            }
        }

        fun createMemo(
            content: String,
            onSuccess: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                runCatching {
                    createMemoUseCase(content = content, timestampMillis = System.currentTimeMillis())
                    onSuccess?.invoke()
                    clearTrackedImages()
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
                    clearTrackedImages()
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
                    trackedImageFilenames += path
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

        fun discardInputs() {
            viewModelScope.launch {
                runCatching {
                    val toDelete = trackedImageFilenames.toList()
                    trackedImageFilenames.clear()
                    discardMemoDraftAttachmentsUseCase(toDelete)
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

        private fun clearTrackedImages() {
            trackedImageFilenames.clear()
        }
    }
