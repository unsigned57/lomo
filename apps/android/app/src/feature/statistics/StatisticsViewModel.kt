package com.lomo.app.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.app.feature.common.UiEventQueueCoordinator
import com.lomo.app.feature.common.toUserMessage
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.usecase.MemoStatisticsUseCase
import com.lomo.domain.usecase.PersistShareImageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface StatisticsScreenState {
    data object Loading : StatisticsScreenState

    data class Ready(
        val statistics: MemoStatistics,
    ) : StatisticsScreenState

    data class Error(
        val message: String,
    ) : StatisticsScreenState
}

class StatisticsViewModel(
    private val memoStatisticsUseCase: MemoStatisticsUseCase,
    private val persistShareImageUseCase: PersistShareImageUseCase,
    appConfigStateProvider: com.lomo.app.feature.common.AppConfigStateProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow<StatisticsScreenState>(StatisticsScreenState.Loading)
    val uiState: StateFlow<StatisticsScreenState> = _uiState.asStateFlow()
    private val shareImageEventQueue = UiEventQueueCoordinator<String>()
    val shareImageEvents: StateFlow<List<PendingUiEvent<String>>> = shareImageEventQueue.events
    private val _shareErrorMessage = MutableStateFlow<String?>(null)
    val shareErrorMessage: StateFlow<String?> = _shareErrorMessage.asStateFlow()
    val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences
    private val hasLoaded = MutableStateFlow(false)

    fun refresh() {
        loadStatistics(force = true)
    }

    fun ensureLoaded() {
        if (hasLoaded.value) {
            return
        }
        hasLoaded.value = true
        loadStatistics(force = false)
    }

    fun shareStatisticsImage(source: StatisticsPngSource) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                try {
                    persistShareImageUseCase(
                        fileNamePrefix = STATS_SHARE_FILE_PREFIX,
                        writer = source::writeTo,
                    )
                } finally {
                    source.close()
                }
            }.onSuccess { filePath ->
                _shareErrorMessage.value = null
                shareImageEventQueue.enqueue(filePath)
            }.onFailure { throwable ->
                reportShareFailure(throwable)
            }
        }
    }

    fun reportShareFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        _shareErrorMessage.value = throwable.toUserMessage("Failed to share statistics")
    }

    fun consumeShareImageEvent(eventId: Long) {
        shareImageEventQueue.consume(eventId)
    }

    fun clearShareError() {
        _shareErrorMessage.value = null
    }

    private fun loadStatistics(force: Boolean) {
        if (!force && _uiState.value is StatisticsScreenState.Ready) {
            return
        }
        viewModelScope.launch {
            _uiState.value = StatisticsScreenState.Loading
            runCatching {
                memoStatisticsUseCase()
            }.onSuccess { stats ->
                _uiState.value = StatisticsScreenState.Ready(stats)
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    throw throwable
                }
                _uiState.value = StatisticsScreenState.Error(throwable.toUserMessage("Failed to load statistics"))
            }
        }
    }

    private companion object {
        const val STATS_SHARE_FILE_PREFIX = "stats_share"
    }
}
