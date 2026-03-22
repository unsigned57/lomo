package com.lomo.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.provider.ImageMapProvider
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.usecase.DailyReviewQueryUseCase
import com.lomo.domain.usecase.DeleteMemoUseCase
import com.lomo.domain.usecase.SaveImageResult
import com.lomo.domain.usecase.SaveImageUseCase
import com.lomo.domain.usecase.UpdateMemoContentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DailyReviewViewModel
    @Inject
    constructor(
        private val memoUiCoordinator: MemoUiCoordinator,
        private val appConfigUiCoordinator: AppConfigUiCoordinator,
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
            appConfigUiCoordinator
                .appPreferences()
                .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), AppPreferencesState.defaults())

        val activeDayCount: StateFlow<Int> =
            memoUiCoordinator
                .activeDayCount()
                .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0)

        val rootDirectory: StateFlow<String?> =
            appConfigUiCoordinator
                .rootDirectory()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = null,
                )

        val imageDirectory: StateFlow<String?> =
            appConfigUiCoordinator
                .imageDirectory()
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
                        val rawMemos = dailyReviewQueryUseCase()

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
                    _errorMessage.value = e.toUserMessage("Failed to update memo")
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
                    _errorMessage.value = e.toUserMessage("Failed to delete memo")
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
                    onResult(path)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = e.toUserMessage("Failed to save image")
                    onError?.invoke()
                }
            }
        }

        fun clearError() {
            _errorMessage.value = null
        }

        private data class UiMemoMappingInput(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, android.net.Uri>,
        )
    }
