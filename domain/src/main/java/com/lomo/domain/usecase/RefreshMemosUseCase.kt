package com.lomo.domain.usecase

import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class RefreshMemosUseCase
    @Inject
    constructor(
        private val memoRepository: MemoRepository,
        private val gitSyncRepository: GitSyncRepository,
    ) {
        suspend operator fun invoke() {
            val syncOnRefresh = gitSyncRepository.getSyncOnRefreshEnabled().first()
            val gitEnabled = gitSyncRepository.isGitSyncEnabled().first()
            if (syncOnRefresh && gitEnabled) {
                try {
                    gitSyncRepository.sync()
                } catch (_: Exception) {
                    // Best effort sync.
                }
            }
            memoRepository.refreshMemos()
        }
    }
