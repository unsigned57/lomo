package com.lomo.app.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.usecase.MemoStatisticsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatisticsViewModel
    @Inject
    constructor(
        private val memoStatisticsUseCase: MemoStatisticsUseCase,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<UiState<MemoStatistics>>(UiState.Loading)
        val uiState: StateFlow<UiState<MemoStatistics>> = _uiState.asStateFlow()

        init {
            loadStatistics()
        }

        fun refresh() {
            loadStatistics()
        }

        private fun loadStatistics() {
            viewModelScope.launch {
                _uiState.value = UiState.Loading
                runCatching {
                    memoStatisticsUseCase()
                }.onSuccess { stats ->
                    _uiState.value = UiState.Success(stats)
                }.onFailure { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException) {
                        throw throwable
                    }
                    _uiState.value = UiState.Error(throwable.toUserMessage("Failed to load statistics"))
                }
            }
        }
    }
