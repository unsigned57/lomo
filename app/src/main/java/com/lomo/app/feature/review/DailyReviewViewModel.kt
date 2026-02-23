package com.lomo.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.media.MemoImageWorkflow
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.observeAppPreferences
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SettingsRepository
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.UpdateMemoUseCase
import com.lomo.ui.util.UiState
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DailyReviewViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val imageWorkflow: MemoImageWorkflow,
        private val settingsRepository: SettingsRepository,
        private val mapper: com.lomo.app.feature.main.MemoUiMapper,
        private val imageMapProvider: ImageMapProvider,
        private val updateMemoUseCase: UpdateMemoUseCase,
        private val deleteMemoUseCase: DeleteMemoUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>>(UiState.Loading)
        val uiState: StateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>> = _uiState.asStateFlow()
        private var loadJob: Job? = null

        private val defaultPreferences = AppPreferencesState.defaults()

        val appPreferences: StateFlow<AppPreferencesState> =
            settingsRepository
                .observeAppPreferences()
                .stateInViewModel(viewModelScope, defaultPreferences)

        val activeDayCount: StateFlow<Int> =
            repository
                .getActiveDayCount()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = 0,
                )

        val imageDirectory: StateFlow<String?> =
            repository
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
                        val rawMemos = repository.getDailyReviewMemos(10, today)

                        if (rawMemos.isEmpty()) {
                            _uiState.value = UiState.Success(emptyList())
                            return@launch
                        }

                        // Combine with image configuration streams to process content
                        kotlinx.coroutines.flow
                            .combine(
                                repository.getImageDirectory(),
                                imageMapProvider.imageMap,
                            ) { imageDir, imageMap ->
                                imageDir to imageMap
                            }.mapLatest { (imageDir, imageMap) ->
                                mapper.mapToUiModels(
                                    memos = rawMemos,
                                    rootPath = null,
                                    imagePath = imageDir,
                                    imageMap = imageMap,
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
                try {
                    updateMemoUseCase(memo, newContent)
                    loadDailyReview()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep current state on failure.
                }
            }
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                try {
                    deleteMemoUseCase(memo)
                    loadDailyReview()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep current state on failure.
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
                    val path = imageWorkflow.saveImageAndSync(uri)
                    onResult(path)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep Daily Review UI resilient; skip insertion on failure.
                    onError?.invoke()
                }
            }
        }
    }
