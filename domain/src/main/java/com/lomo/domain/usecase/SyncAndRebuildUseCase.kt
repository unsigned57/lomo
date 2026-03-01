package com.lomo.domain.usecase

import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class SyncAndRebuildUseCase
    constructor(
        private val memoRepository: MemoRepository,
        private val gitSyncRepository: GitSyncRepository,
    ) {
        suspend operator fun invoke(forceSync: Boolean = false) {
            if (forceSync) {
                val syncFailure = syncFailureOrNull()

                memoRepository.refreshMemos()
                syncFailure?.let { throw it }
                return
            }

            val syncOnRefresh = gitSyncRepository.getSyncOnRefreshEnabled().first()
            val gitEnabled = gitSyncRepository.isGitSyncEnabled().first()
            if (syncOnRefresh && gitEnabled) {
                // Best-effort sync for non-forced refresh.
                syncFailureOrNull()
            }

            memoRepository.refreshMemos()
        }

        private suspend fun syncFailureOrNull(): Exception? =
            try {
                when (val result = gitSyncRepository.sync()) {
                    is GitSyncResult.Success -> null
                    is GitSyncResult.Error -> result.toException()
                    GitSyncResult.NotConfigured ->
                        SyncFailureException("Git sync is not configured")
                    GitSyncResult.DirectPathRequired ->
                        SyncFailureException("Git sync requires a direct local directory path")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }

        private fun GitSyncResult.Error.toException(): Exception {
            val cause = exception
            val normalizedMessage = message.ifBlank { "Git sync failed" }
            if (cause is CancellationException) {
                throw cause
            }

            if (cause is Exception) {
                return SyncFailureException(message = normalizedMessage, cause = cause)
            }

            return SyncFailureException(
                message = normalizedMessage,
                cause = cause,
            )
        }

        private class SyncFailureException(
            message: String,
            cause: Throwable? = null,
        ) : Exception(message, cause)
    }
