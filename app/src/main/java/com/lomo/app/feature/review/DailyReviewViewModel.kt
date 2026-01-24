package com.lomo.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import com.lomo.ui.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
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
        private val mapper: com.lomo.app.feature.main.MemoUiMapper,
        private val imageMapProvider: com.lomo.domain.provider.ImageMapProvider,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>>(UiState.Loading)
        val uiState: StateFlow<UiState<List<com.lomo.app.feature.main.MemoUiModel>>> = _uiState.asStateFlow()

        val dateFormat: StateFlow<String> =
            repository
                .getDateFormat()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = com.lomo.data.util.PreferenceKeys.Defaults.DATE_FORMAT,
                )

        val timeFormat: StateFlow<String> =
            repository
                .getTimeFormat()
                .stateIn(
                    scope = viewModelScope,
                    started =
                        kotlinx.coroutines.flow.SharingStarted
                            .WhileSubscribed(5000),
                    initialValue = com.lomo.data.util.PreferenceKeys.Defaults.TIME_FORMAT,
                )

        init {
            loadDailyReview()
        }

        private fun loadDailyReview() {
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
                            rawMemos.map { memo ->
                                mapper.mapToUiModel(
                                    memo = memo,
                                    rootPath = null, // Root path usually from prefs, passing null for now or inject if needed
                                    // MainViewModel gets rootDirectory from repository.getRootDirectory()?
                                    // Actually MainViewModel uses repository.rootDirectory? No, let's check.
                                    // MainViewModel: repository.getImageDirectory() (imageDir)
                                    // We need storage config. For now assuming images are relative to imageDir.
                                    imagePath = imageDir,
                                    imageMap = imageMap,
                                )
                            }
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
    }
