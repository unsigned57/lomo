package com.lomo.data.repository

import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncStatus
import com.lomo.domain.repository.WebDavSyncConfigurationMutationRepository
import com.lomo.domain.repository.WebDavSyncConfigurationRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.repository.WebDavSyncStateRepository

/**
 * Post P5-13 production WebDAV facade: config/credentials + Rust work enqueue only.
 * Replaces deleted [WebDavSyncRepositoryImpl] business owner.
 */
class WebDavRemoteSyncFacade(
    private val configuration: WebDavSyncConfigurationRepository,
    private val configurationMutation: WebDavSyncConfigurationMutationRepository,
    private val state: WebDavSyncStateRepository,
    private val rustSyncScheduler: RustSyncScheduler,
) : WebDavSyncRepository,
    WebDavSyncConfigurationRepository by configuration,
    WebDavSyncConfigurationMutationRepository by configurationMutation,
    WebDavSyncStateRepository by state {
    override suspend fun sync(): WebDavSyncResult = enqueueRustCycle("WebDAV sync enqueued")

    override suspend fun getStatus(): WebDavSyncStatus =
        WebDavSyncStatus(
            remoteFileCount = 0,
            localFileCount = 0,
            pendingChanges = 0,
            lastSyncTime = null,
        )

    override suspend fun testConnection(): WebDavSyncResult = enqueueRustCycle("WebDAV connection test enqueued")

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): WebDavSyncResult =
        WebDavSyncResult.Error(
            code = WebDavSyncErrorCode.UNKNOWN,
            message = "Resolve conflicts in Sync Center",
        )

    override suspend fun resolveReview(
        resolution: SyncReviewResolution,
        review: SyncReviewSession,
    ): WebDavSyncResult =
        WebDavSyncResult.Error(
            code = WebDavSyncErrorCode.UNKNOWN,
            message = "WebDAV review sessions are retired; use Sync Center / Sync Inbox",
        )

    private suspend fun enqueueRustCycle(message: String): WebDavSyncResult {
        rustSyncScheduler.enqueueOneShot(secretFieldKey = "WEBDAV_PASSWORD")
        return WebDavSyncResult.Success(message)
    }
}
