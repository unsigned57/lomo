package com.lomo.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.memo.MemoActionDelegate
import com.lomo.app.feature.memo.MemoFlowProcessor
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.preferences.activeDayCountState
import com.lomo.app.feature.preferences.appPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.DirectorySettingsRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.ui.util.UiState
import com.lomo.ui.util.stateInViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DailyReviewViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val directorySettings: DirectorySettingsRepository,
        private val preferencesRepository: PreferencesRepository,
        private val imageMapProvider: ImageMapProvider,
        private val memoFlowProcessor: MemoFlowProcessor,
        private val memoActionDelegate: MemoActionDelegate,
        private val dailyReviewQueryUseCase: DailyReviewQueryUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>>(UiState.Loading)
        val uiState: StateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>> = _uiState.asStateFlow()
        private var loadJob: Job? = null

        val appPreferences: StateFlow<AppPreferencesState> =
            preferencesRepository.appPreferencesState(viewModelScope)

        val activeDayCount: StateFlow<Int> =
            repository.activeDayCountState(viewModelScope)

        val imageDirectory: StateFlow<String?> =
            directorySettings
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

                        memoFlowProcessor
                            .mapMemoSnapshot(
                                memos = rawMemos,
                                rootDirectory = kotlinx.coroutines.flow.flowOf(null),
                                imageDirectory = imageDirectory,
                                imageMap = imageMapProvider.imageMap,
                            ).collect { uiModels ->
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
                val success =
                    memoActionDelegate
                        .updateMemo(memo, newContent)
                        .isSuccess
                if (success) {
                    loadDailyReview()
                }
            }
        }

        fun deleteMemo(memo: Memo) {
            viewModelScope.launch {
                val success = memoActionDelegate.deleteMemo(memo).isSuccess
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
                memoActionDelegate
                    .saveImage(uri)
                    .onSuccess(onResult)
                    .onFailure {
                        // Keep Daily Review UI resilient; skip insertion on failure.
                        onError?.invoke()
                    }
            }
        }
    }
