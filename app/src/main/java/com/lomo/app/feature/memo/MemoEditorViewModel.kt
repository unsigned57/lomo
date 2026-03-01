package com.lomo.app.feature.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.InitializeWorkspaceUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.ValidateMemoContentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoEditorViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val initializeWorkspaceUseCase: InitializeWorkspaceUseCase,
        private val validator: ValidateMemoContentUseCase,
        private val saveImageUseCase: SaveImageUseCase,
        private val mediaRepository: MediaRepository,
        private val appWidgetRepository: AppWidgetRepository,
    ) : ViewModel() {
        private val trackedImageFilenames = mutableSetOf<String>()

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        fun createMemo(
            content: String,
            onSuccess: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                try {
                    if (initializeWorkspaceUseCase.currentRootLocation() == null) {
                        _errorMessage.value = "Please select a folder first"
                        return@launch
                    }
                    validator.validateForCreate(content)
                    repository.saveMemo(content, System.currentTimeMillis())
                    onSuccess?.invoke()
                    clearTrackedImages()
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
                    validator.validateForUpdate(newContent)
                    repository.updateMemo(memo, newContent)
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
                    val path = saveImageUseCase(uri.toString())
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
                    discardTrackedImages()
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

        private suspend fun discardTrackedImages() {
            val toDelete = trackedImageFilenames.toList()
            trackedImageFilenames.clear()

            toDelete.forEach { filename ->
                try {
                    mediaRepository.removeImage(MediaEntryId(filename))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best-effort cleanup.
                }
            }

            try {
                mediaRepository.refreshImageLocations()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort refresh.
            }
        }
    }
