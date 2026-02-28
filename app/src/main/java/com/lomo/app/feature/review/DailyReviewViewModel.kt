package com.lomo.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.activeDayCountState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.AppConfigRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import com.lomo.ui.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DailyReviewViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val appConfigRepository: AppConfigRepository,
        private val imageMapProvider: ImageMapProvider,
        private val memoUiMapper: MemoUiMapper,
        private val deleteMemoUseCase: DeleteMemoUseCase,
        private val updateMemoContentUseCase: UpdateMemoContentUseCase,
        private val saveImageUseCase: SaveImageUseCase,
        private val dailyReviewQueryUseCase: DailyReviewQueryUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>>(UiState.Loading)
        val uiState: StateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>> = _uiState.asStateFlow()
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
        private var loadJob: Job? = null

        val appPreferences: StateFlow<AppPreferencesState> =
            appConfigRepository.appPreferencesState(viewModelScope)

        val activeDayCount: StateFlow<Int> =
            repository.activeDayCountState(viewModelScope)

        val rootDirectory: StateFlow<String?> =
            appConfigRepository
                .getRootDirectory()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = null,
                )

        val imageDirectory: StateFlow<String?> =
            appConfigRepository
                .getImageDirectory()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = null,
                )

        init {
            loadDailyReview()
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private fun loadDailyReview() {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    _uiState.value = UiState.Loading
                    try {
                        // Use today's date for seeded random
                        val today = java.time.LocalDate.now()
                        val rawMemos = dailyReviewQueryUseCase(limit = 10, seedDate = today)

                        if (rawMemos.isEmpty()) {
                            _uiState.value = UiState.Success(emptyList())
                            return@launch
                        }

                        combine(rootDirectory, imageDirectory, imageMapProvider.imageMap) {
                                rootDir,
                                imageDir,
                                currentImageMap,
                            ->
                            UiMemoMappingInput(
                                memos = rawMemos,
                                rootDirectory = rootDir,
                                imageDirectory = imageDir,
                                imageMap = currentImageMap,
                            )
                        }.distinctUntilChanged()
                            .mapLatest { input ->
                                memoUiMapper.mapToUiModels(
                                    memos = input.memos,
                                    rootPath = input.rootDirectory,
                                    imagePath = input.imageDirectory,
                                    imageMap = input.imageMap,
                                )
                            }.collect { uiModels ->
                                _uiState.value = UiState.Success(uiModels)
                            }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _uiState.value = UiState.Error("Failed to load daily review", e)
                    }
                }
        }

        fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            viewModelScope.launch {
                var success = false
                try {
                    updateMemoContentUseCase(memo, newContent)
                    success = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to update memo")
                }
                if (success) {
                    loadDailyReview()
                }
            }
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                var success = false
                try {
                    deleteMemoUseCase(memo)
                    success = true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to delete memo")
                }
                if (success) {
                    loadDailyReview()
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
                    onResult(saveImageUseCase(uri.toString()))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.userMessage("Failed to save image")
                    onError?.invoke()
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }

        private fun Throwable.userMessage(prefix: String): String =
            if (message.isNullOrBlank()) {
                prefix
            } else {
                "$prefix: ${message.orEmpty()}"
            }

        private data class UiMemoMappingInput(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
        )
    }
