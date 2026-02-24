package com.lomo.app.feature.memo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.main.MainMediaCoordinator
import com.lomo.app.repository.AppWidgetRepository
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.domain.validation.MemoContentValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoEditorViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val settingsRepository: SettingsRepository,
        private val validator: MemoContentValidator,
        private val mediaCoordinator: MainMediaCoordinator,
        private val appWidgetRepository: AppWidgetRepository,
    ) : ViewModel() {
        val controller = MemoEditorController()

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage

        fun openForCreate(initialText: String = "") {
            controller.openForCreate(initialText)
        }

        fun appendSharedText(text: String) {
            val current = controller.inputValue.text
            val newText = if (current.isEmpty()) text else "$current\n$text"
            controller.updateInputValue(TextFieldValue(newText, TextRange(newText.length)))
            controller.ensureVisible()
        }

        fun createMemo(
            content: String,
            onSuccess: (() -> Unit)? = null,
        ) {
            viewModelScope.launch {
                try {
                    if (settingsRepository.getRootDirectoryOnce() == null) {
                        _errorMessage.value = "Please select a folder first"
                        return@launch
                    }
                    validator.validateForCreate(content)
                    repository.saveMemo(content)
                    appWidgetRepository.updateAllWidgets()
                    mediaCoordinator.clearTrackedImages()
                    onSuccess?.invoke()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage()
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
                    mediaCoordinator.clearTrackedImages()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage()
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
                    onResult(mediaCoordinator.saveImageAndTrack(uri))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to save image")
                    onError?.invoke()
                }
            }
        }

        fun discardInputs() {
            viewModelScope.launch {
                try {
                    mediaCoordinator.discardTrackedImages()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to discard input")
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }

        private fun Throwable.userMessage(prefix: String? = null): String =
            when {
                prefix.isNullOrBlank() && message.isNullOrBlank() -> "Unexpected error"
                prefix.isNullOrBlank() -> message.orEmpty()
                message.isNullOrBlank() -> prefix
                else -> "$prefix: $message"
            }
    }
