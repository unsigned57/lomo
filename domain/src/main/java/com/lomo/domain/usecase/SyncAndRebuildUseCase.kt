package com.lomo.domain.usecase

import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SyncAndRebuildUseCase
    @Inject
    constructor(
        private val memoRepository: MemoRepository,
        private val gitSyncRepository: GitSyncRepository,
    ) {
        suspend operator fun invoke(forceSync: Boolean = false) {
            if (forceSync) {
                var syncFailure: Exception? = null
                try {
                    gitSyncRepository.sync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    syncFailure = e
                }

                memoRepository.refreshMemos()
                syncFailure?.let { throw it }
                return
            }

            val syncOnRefresh = gitSyncRepository.getSyncOnRefreshEnabled().first()
            val gitEnabled = gitSyncRepository.isGitSyncEnabled().first()
            if (syncOnRefresh && gitEnabled) {
                try {
                    gitSyncRepository.sync()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best effort sync.
                }
            }

            memoRepository.refreshMemos()
        }
    }
