package com.lomo.app.feature.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.CreateMemoUseCase
import com.lomo.domain.usecase.DiscardMemoDraftAttachmentsUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.domain.repository.PreferencesRepository
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
        private val preferencesRepository: PreferencesRepository,
    ) : ViewModel() {
        private val trackedImageFilenames = mutableSetOf<String>()

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        private val _draftText = MutableStateFlow(
            runBlocking { preferencesRepository.getDraftText().first() },
        )
        val draftText: StateFlow<String> = _draftText

        fun saveDraft(text: String) {
            _draftText.value = text
            viewModelScope.launch {
                preferencesRepository.setDraftText(text)
            }
        }

        fun clearDraft() {
            _draftText.value = ""
            viewModelScope.launch {
                preferencesRepository.setDraftText(null)
            }
        }

        fun createMemo(
            content: String,
            onSuccess: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                try {
                    createMemoUseCase(content = content, timestampMillis = System.currentTimeMillis())
                    onSuccess?.invoke()
                    clearTrackedImages()
                    clearDraft()
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { appWidgetRepository.updateAllWidgets() }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage()
                }
            }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                try {
                    updateMemoContentUseCase(memo, newContent)
                    appWidgetRepository.updateAllWidgets()
                    clearTrackedImages()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage()
                }
            }
        }

        fun saveImage(
            uri: android.net.Uri,
            onResult: (String) -> Unit,
            onError: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                try {
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to save image")
                    onError?.invoke()
                }
            }
        }

        fun discardInputs() {
            viewModelScope.launch {
                try {
                    val toDelete = trackedImageFilenames.toList()
                    trackedImageFilenames.clear()
                    discardMemoDraftAttachmentsUseCase(toDelete)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to discard input")
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
