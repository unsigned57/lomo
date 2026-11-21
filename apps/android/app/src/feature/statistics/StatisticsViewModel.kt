package com.lomo.app.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.PendingUiEvent
import com.lomo.app.feature.common.UiEventEnqueueResult
import com.lomo.app.feature.common.UiEventQueueCoordinator
import com.lomo.app.feature.common.reportUserFailure
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.usecase.MemoStatisticsUseCase
import com.lomo.domain.usecase.PersistShareImageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface StatisticsScreenState {
    data object Loading : StatisticsScreenState

    data class Ready(val statistics: MemoStatistics) : StatisticsScreenState

    data object Error : StatisticsScreenState
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModel(
    private val diagnostics: EngineDiagnosticsRecorder,
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
    private val statisticsCollectionAttempt = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            statisticsCollectionAttempt
                .flatMapLatest {
                    // The catch lives inside the attempt-scoped inner flow: an upstream-level
                    // catch would end the whole collection after one failure, so retry could
                    // never trigger a recollection. Failure becomes a state value here so the
                    // derivation stays pure; the collect sink owns every state write.
                    memoStatisticsUseCase.observe()
                        .map<MemoStatistics, StatisticsScreenState> { stats ->
                            StatisticsScreenState.Ready(stats)
                        }.catch { throwable ->
                            if (throwable is CancellationException) throw throwable
                            Timber.w(throwable, "statistics failure")
                            emit(StatisticsScreenState.Error)
                        }
                }.collect { state ->
                    _uiState.value = state
                }
        }
    }

    fun retry() {
        _uiState.value = StatisticsScreenState.Loading
        statisticsCollectionAttempt.value += 1
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
        _shareErrorMessage.value = diagnostics.reportUserFailure(
            "statistics.share",
            throwable,
            "Failed to share statistics",
        )
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
