package com.lomo.app.feature.main

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

sealed interface MainVersionHistoryState {
    data object Hidden : MainVersionHistoryState

    data object Loading : MainVersionHistoryState

    data class Loaded(
        val memo: Memo,
        val versions: List<MemoRevision>,
        val nextCursor: MemoRevisionCursor?,
        val isLoadingMore: Boolean = false,
        val isRestoring: Boolean = false,
    ) : MainVersionHistoryState {
        val hasMore: Boolean
            get() = nextCursor != null
    }
}

class MainVersionHistoryCoordinator
    @Inject
    constructor(
        private val loadMemoRevisionHistoryUseCase: LoadMemoRevisionHistoryUseCase,
        private val restoreMemoRevisionUseCase: RestoreMemoRevisionUseCase,
    ) {
        private val _state = MutableStateFlow<MainVersionHistoryState>(MainVersionHistoryState.Hidden)
        val state: StateFlow<MainVersionHistoryState> = _state.asStateFlow()

        fun historyEnabled() = loadMemoRevisionHistoryUseCase.historyEnabled()

        suspend fun load(memo: Memo) {
            _state.value = MainVersionHistoryState.Loading
            val page = loadMemoRevisionHistoryUseCase(memo)
            _state.value =
                MainVersionHistoryState.Loaded(
                    memo = memo,
                    versions = page.items,
                    nextCursor = page.nextCursor,
                )
        }

        suspend fun loadMore() {
            val current = _state.value as? MainVersionHistoryState.Loaded ?: return
            val cursor = current.nextCursor ?: return
            if (current.isLoadingMore || current.isRestoring) {
                return
            }
            _state.value = current.copy(isLoadingMore = true)
            runCatching {
                loadMemoRevisionHistoryUseCase(current.memo, cursor)
            }.onSuccess { page ->
                _state.value =
                    current.copy(
                        versions = current.versions + page.items,
                        nextCursor = page.nextCursor,
                        isLoadingMore = false,
                    )
            }.onFailure { throwable ->
                _state.value = current.copy(isLoadingMore = false)
                throw throwable
            }
        }

        suspend fun restore(
            memo: Memo,
            version: MemoRevision,
        ) {
            if (version.isCurrent) {
                return
            }
            val current = _state.value as? MainVersionHistoryState.Loaded
            if (current?.isRestoring == true) {
                return
            }
            if (current != null) {
                _state.value = current.copy(isRestoring = true)
            }
            runCatching {
                restoreMemoRevisionUseCase(memo, version)
            }.onSuccess {
                _state.value = MainVersionHistoryState.Hidden
            }.onFailure { throwable ->
                if (current != null) {
                    _state.value = current.copy(isRestoring = false)
                }
                throw throwable
            }
        }

        fun hide() {
            _state.value = MainVersionHistoryState.Hidden
        }
    }
