package com.lomo.data.repository

import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.UnifiedSyncState
import com.lomo.domain.repository.GitSyncConfigurationMutationRepository
import com.lomo.domain.repository.GitSyncConfigurationRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.GitSyncStateRepository
import kotlinx.coroutines.flow.Flow

/**
 * Post P5-13 production Git facade: DataStore config + Keystore credentials + Rust work enqueue.
 * Force/reset and JGit business ownership are deleted; operations enqueue [RustSyncScheduler].
 */
class GitRemoteSyncFacade(
    private val configuration: GitSyncConfigurationRepository,
    private val configurationMutation: GitSyncConfigurationMutationRepository,
    private val state: GitSyncStateRepository,
    private val rustSyncScheduler: RustSyncScheduler,
) : GitSyncRepository,
    GitSyncConfigurationRepository by configuration,
    GitSyncConfigurationMutationRepository by configurationMutation,
    GitSyncStateRepository by state {
    override suspend fun initOrClone(): GitSyncResult = enqueueRustCycle("Git init/clone enqueued")

    override suspend fun sync(): GitSyncResult = enqueueRustCycle("Git sync enqueued")

    override suspend fun getStatus(): GitSyncStatus =
        GitSyncStatus(
            hasLocalChanges = false,
            aheadCount = 0,
            behindCount = 0,
            lastSyncTime = null,
        )

    override suspend fun testConnection(): GitSyncResult = enqueueRustCycle("Git connection test enqueued")

    override suspend fun resetRepository(): GitSyncResult =
        GitSyncResult.Error(
            code = GitSyncErrorCode.UNKNOWN,
            message = "Git force/reset is permanently removed; use Sync Center recovery",
        )

    override suspend fun resetLocalBranchToRemote(): GitSyncResult =
        GitSyncResult.Error(
            code = GitSyncErrorCode.UNKNOWN,
            message = "Git force/reset is permanently removed; use Sync Center recovery",
        )

    override suspend fun forcePushLocalToRemote(): GitSyncResult =
        GitSyncResult.Error(
            code = GitSyncErrorCode.UNKNOWN,
            message = "Git force/reset is permanently removed; use Sync Center recovery",
        )

    override suspend fun resolveConflicts(
        resolution: SyncConflictResolution,
        conflictSet: SyncConflictSet,
    ): GitSyncResult =
        GitSyncResult.Error(
            code = GitSyncErrorCode.CONFLICT,
            message = "Resolve conflicts in Sync Center",
        )

    private suspend fun enqueueRustCycle(message: String): GitSyncResult {
        rustSyncScheduler.enqueueOneShot(secretFieldKey = "GIT_TOKEN")
        return GitSyncResult.Success(message)
    }
}
