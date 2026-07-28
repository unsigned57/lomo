package com.lomo.data.repository

import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncStatus
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.repository.S3SyncConfigurationMutationRepository
import com.lomo.domain.repository.S3SyncConfigurationRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.S3SyncStateRepository

/**
 * Post P5-13 production S3 facade: config/credentials + Rust work enqueue only.
 * Replaces deleted [S3SyncRepositoryImpl] business owner.
 */
class S3RemoteSyncFacade(
    private val configuration: S3SyncConfigurationRepository,
    private val configurationMutation: S3SyncConfigurationMutationRepository,
    private val state: S3SyncStateRepository,
    private val rustSyncScheduler: RustSyncScheduler,
) : S3SyncRepository,
    S3SyncConfigurationRepository by configuration,
    S3SyncConfigurationMutationRepository by configurationMutation,
    S3SyncStateRepository by state {
    override suspend fun sync(): S3SyncResult = enqueueRustCycle("S3 sync enqueued")

    override suspend fun getStatus(): S3SyncStatus =
        S3SyncStatus(
            remoteFileCount = 0,
            localFileCount = 0,
            pendingChanges = 0,
            lastSyncTime = null,
        )

    override suspend fun testConnection(): S3SyncResult = enqueueRustCycle("S3 connection test enqueued")

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): S3SyncResult =
        S3SyncResult.Error(
            code = S3SyncErrorCode.UNKNOWN,
            message = "Resolve conflicts in Sync Center",
        )

    override suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): S3SyncResult =
        S3SyncResult.Error(
            code = S3SyncErrorCode.UNKNOWN,
            message = "S3 review sessions are retired; use Sync Center / Sync Inbox",
        )

    private suspend fun enqueueRustCycle(message: String): S3SyncResult {
        rustSyncScheduler.enqueueOneShot(secretFieldKey = "S3_SECRET_ACCESS_KEY")
        return S3SyncResult.Success(message)
    }
}
