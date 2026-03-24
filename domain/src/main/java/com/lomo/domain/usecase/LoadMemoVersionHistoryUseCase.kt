package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoVersion
import com.lomo.domain.repository.GitSyncRepository
import kotlinx.coroutines.flow.Flow

class LoadMemoVersionHistoryUseCase
(
        private val gitSyncRepository: GitSyncRepository,
    ) {
        fun observeGitSyncEnabled(): Flow<Boolean> = gitSyncRepository.isGitSyncEnabled()

        suspend operator fun invoke(memo: Memo): List<MemoVersion> =
            gitSyncRepository.getMemoVersionHistory(
                memo.dateKey,
                memo.timestamp,
            )
    }
