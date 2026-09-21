package com.lomo.app.feature.main

import com.lomo.app.feature.common.newMemoOperationId
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.usecase.LoadMemoRevisionHistoryUseCase
import com.lomo.domain.usecase.RestoreMemoRevisionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


sealed interface MainVersionHistoryState {
    data object Hidden : MainVersionHistoryState

    data object Loading : MainVersionHistoryState

    data class Loaded(
        val memo: Memo,
        val versions: List<MemoRevision>,
        val nextCursor: MemoRevisionCursor?,
        val isLoadingMore: Boolean = false,
        val isRestoring: Boolean = false,
        val restoringRevisionId: String? = null,
    ) : MainVersionHistoryState {
        val hasMore: Boolean
            get() = nextCursor != null
    }
}

class MainVersionHistoryCoordinator(
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
            var completed = false
            try {
                val page = loadMemoRevisionHistoryUseCase(current.memo, cursor)
                _state.value =
                    current.copy(
                        versions = current.versions + page.items,
                        nextCursor = page.nextCursor,
                        isLoadingMore = false,
                    )
                completed = true
            } finally {
                if (!completed) {
                    _state.value = current.copy(isLoadingMore = false)
                }
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
                _state.value =
                    current.copy(
                        isRestoring = true,
                        restoringRevisionId = version.revisionId,
                    )
            }
            var completed = false
            try {
                restoreMemoRevisionUseCase(memo, version, newMemoOperationId())
                _state.value = MainVersionHistoryState.Hidden
                completed = true
            } finally {
                if (!completed && current != null) {
                    _state.value =
                        current.copy(
                            isRestoring = false,
                            restoringRevisionId = null,
                        )
                }
            }
        }

        fun hide() {
            _state.value = MainVersionHistoryState.Hidden
        }
    }
