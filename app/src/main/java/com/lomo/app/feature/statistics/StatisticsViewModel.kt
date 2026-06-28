package com.lomo.app.feature.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigStateProvider
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.app.feature.common.toUserMessage
import com.lomo.domain.model.MemoStatistics
import com.lomo.domain.usecase.MemoStatisticsUseCase
import com.lomo.domain.usecase.PersistShareImageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatisticsShareImageEvent(
    val id: Long,
    val filePath: String,
)

@HiltViewModel
class StatisticsViewModel
    @Inject
    constructor(
        private val memoStatisticsUseCase: MemoStatisticsUseCase,
        private val persistShareImageUseCase: PersistShareImageUseCase,
        appConfigStateProvider: AppConfigStateProvider,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<UiState<MemoStatistics>>(UiState.Loading)
        val uiState: StateFlow<UiState<MemoStatistics>> = _uiState.asStateFlow()
        private val _shareImageEvent = MutableStateFlow<StatisticsShareImageEvent?>(null)
        val shareImageEvent: StateFlow<StatisticsShareImageEvent?> = _shareImageEvent.asStateFlow()
        private val _shareErrorMessage = MutableStateFlow<String?>(null)
        val shareErrorMessage: StateFlow<String?> = _shareErrorMessage.asStateFlow()
        val appPreferences: StateFlow<AppPreferencesState> = appConfigStateProvider.appPreferences
        private var nextShareEventId = 0L
        private var hasLoaded = false

        fun refresh() {
            loadStatistics(force = true)
        }

        fun ensureLoaded() {
            if (hasLoaded) {
                return
            }
            hasLoaded = true
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
                    _shareImageEvent.value =
                        StatisticsShareImageEvent(
                            id = nextShareEventId(),
                            filePath = filePath,
                        )
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
            if (_shareImageEvent.value?.id == eventId) {
                _shareImageEvent.value = null
            }
        }

        fun clearShareError() {
            _shareErrorMessage.value = null
        }

        private fun loadStatistics(force: Boolean) {
            if (!force && _uiState.value is UiState.Success) {
                return
            }
            viewModelScope.launch {
                _uiState.value = UiState.Loading
                runCatching {
                    memoStatisticsUseCase()
                }.onSuccess { stats ->
                    _uiState.value = UiState.Success(stats)
                }.onFailure { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    _uiState.value = UiState.Error(throwable.toUserMessage("Failed to load statistics"))
                }
            }
        }

        private fun nextShareEventId(): Long {
            nextShareEventId += 1
            return nextShareEventId
        }

        private companion object {
            const val STATS_SHARE_FILE_PREFIX = "stats_share"
        }
    }
