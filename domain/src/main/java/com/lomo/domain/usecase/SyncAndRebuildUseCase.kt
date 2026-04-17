package com.lomo.domain.usecase

import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncOperation
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.SyncPolicyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class SyncAndRebuildUseCase
(
        private val memoRepository: MemoRepository,
        private val syncProviderRegistry: SyncProviderRegistry,
        private val syncPolicyRepository: SyncPolicyRepository,
    ) {
        suspend operator fun invoke(forceSync: Boolean = false) {
            syncProviderRegistry
                .get(SyncBackendType.INBOX)
                ?.sync(UnifiedSyncOperation.PROCESS_PENDING_CHANGES)
                ?.toSyncFailureOrNull()
                ?.let { throw it }

            val activeBackend = activeBackend()
            val activeProvider = syncProviderRegistry.active(activeBackend)

            if (forceSync) {
                val syncFailure =
                    if (activeProvider == null) {
                        null
                    } else {
                        syncFailureOrNull(
                            backendType = activeBackend,
                            operation = UnifiedSyncOperation.MANUAL_SYNC,
                        )
                    }
                memoRepository.refreshMemos()
                syncFailure?.let { throw it }
                return
            }

            val syncOnRefresh = activeProvider?.isSyncOnRefreshEnabled()?.first() == true
            val enabled = activeProvider?.isEnabled()?.first() == true
            if (activeProvider != null && syncOnRefresh && enabled) {
                syncFailureOrNull(
                    backendType = activeBackend,
                    operation = UnifiedSyncOperation.REFRESH_SYNC,
                )
            }

            memoRepository.refreshMemos()
        }

        private suspend fun activeBackend(): SyncBackendType = syncPolicyRepository.observeRemoteSyncBackend().first()

        private suspend fun syncFailureOrNull(
            backendType: SyncBackendType,
            operation: UnifiedSyncOperation,
        ): Exception? =
            runCatching {
                syncProviderRegistry
                    .get(backendType)
                    ?.sync(operation)
                    ?.toSyncFailureOrNull()
            }
                .getOrElse { throwable ->
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    throwable as? Exception ?: IllegalStateException(throwable.message, throwable)
                }

    }

class SyncConflictException(
    val conflicts: SyncConflictSet,
) : Exception("Sync conflict detected: ${conflicts.files.size} file(s)")
