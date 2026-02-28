package com.lomo.app.feature.main

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoVersion
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

sealed interface MainVersionHistoryState {
    data object Hidden : MainVersionHistoryState
    data object Loading : MainVersionHistoryState
    data class Loaded(val memo: Memo, val versions: List<MemoVersion>) : MainVersionHistoryState
}

class MainVersionHistoryCoordinator
    @Inject
    constructor(
        private val memoRepository: MemoRepository,
        private val gitSyncRepository: GitSyncRepository,
    ) {
        private val _state = MutableStateFlow<MainVersionHistoryState>(MainVersionHistoryState.Hidden)
        val state: StateFlow<MainVersionHistoryState> = _state.asStateFlow()

        fun syncEnabled(): Flow<Boolean> = gitSyncRepository.isGitSyncEnabled()

        suspend fun load(memo: Memo) {
            _state.value = MainVersionHistoryState.Loading
            val versions = gitSyncRepository.getMemoVersionHistory(memo.dateKey, memo.timestamp)
            _state.value = MainVersionHistoryState.Loaded(memo, versions)
        }

        suspend fun restore(
            memo: Memo,
            version: MemoVersion,
        ) {
            memoRepository.updateMemo(memo, version.memoContent)
            _state.value = MainVersionHistoryState.Hidden
        }

        fun hide() {
            _state.value = MainVersionHistoryState.Hidden
        }
    }
