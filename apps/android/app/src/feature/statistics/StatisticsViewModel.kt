package com.lomo.app.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.app.feature.common.UiEventQueueCoordinator
import com.lomo.app.feature.common.UiEventEnqueueResult
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
import kotlinx.coroutines.flow.catch
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

    init {
        viewModelScope.launch {
            memoStatisticsUseCase
                .observe()
                .catch { throwable ->
                    if (throwable is CancellationException) throw throwable
                    _uiState.value =
                        StatisticsScreenState.Error(
                            throwable.toUserMessage("Failed to load statistics"),
                        )
                }.collect { stats ->
                    _uiState.value = StatisticsScreenState.Ready(stats)
                }
        }
    }

    fun shareStatisticsImage(source: StatisticsPngSource) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val filePath =
                    try {
                        persistShareImageUseCase(
                            fileNamePrefix = STATS_SHARE_FILE_PREFIX,
                            writer = source::writeTo,
                        )
                    } finally {
                        source.close()
                    }
                _shareErrorMessage.value = when (shareImageEventQueue.enqueue(filePath)) {
                    is UiEventEnqueueResult.Accepted -> null
                    is UiEventEnqueueResult.Rejected ->
                        "Too many pending shares. Complete an earlier share and try again."
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                reportShareFailure(error)
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

    private companion object {
        const val STATS_SHARE_FILE_PREFIX = "stats_share"
    }
}
