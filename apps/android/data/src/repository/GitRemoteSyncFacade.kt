package com.lomo.data.repository

import com.lomo.data.engine.sync.RemoteSyncBoundaryFailure
import com.lomo.data.engine.sync.RemoteSyncCycleRequest
import com.lomo.data.engine.sync.RemoteSyncRepository
import com.lomo.data.engine.sync.RustSyncCycleStatusStore
import com.lomo.data.engine.sync.RustSyncSecretSupplier
import com.lomo.data.worker.RustSyncEnqueueOutcome
import com.lomo.data.worker.RustSyncEnqueueRejection
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.data.worker.datasetId
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncResult
import com.lomo.domain.model.GitSyncStatus
import com.lomo.domain.model.identityField
import com.lomo.domain.model.secretField
import com.lomo.domain.repository.GitSyncConfigurationMutationRepository
import com.lomo.domain.repository.GitSyncConfigurationRepository
import com.lomo.domain.repository.GitSyncRepository
import com.lomo.domain.repository.GitSyncStateRepository
import kotlinx.coroutines.flow.first

private const val PROBE_LEASE_TTL_MS: Long = 30_000

/**
 * Post P5-13 production Git facade: DataStore config + Keystore credentials + Rust sync.
 *
 * - `sync`/`initOrClone` enqueue [RustSyncWorker] and return **Accepted** (admission only —
 *   the durable cycle record owns the terminal outcome).
 * - `getStatus`/`syncState` read the durable `cycle_state.rec` via [RustSyncCycleStatusStore].
 * - `testConnection` runs a real `sync_probe_backend` round-trip with the same adapter
 *   construction a cycle uses (never an enqueue acceptance).
 * - `resetRepository` clears the durable sync control tree (`sync_reset_control_tree`) so the
 *   next cycle is a first takeover. Force-push/reset-to-remote and Kotlin-side conflict
 *   resolution are deleted — Sync Center owns conflict/recovery surfaces.
 */
class GitRemoteSyncFacade(
    private val configuration: GitSyncConfigurationRepository,
    private val configurationMutation: GitSyncConfigurationMutationRepository,
    private val state: GitSyncStateRepository,
    private val rustSyncScheduler: RustSyncScheduler,
    private val remoteSync: RemoteSyncRepository,
    private val secretSupplier: RustSyncSecretSupplier,
    private val cycleStatus: RustSyncCycleStatusStore,
) : GitSyncRepository,
    GitSyncConfigurationRepository by configuration,
    GitSyncConfigurationMutationRepository by configurationMutation,
    GitSyncStateRepository by state {
    override suspend fun initOrClone(): GitSyncResult = enqueueRustCycle("Git init/clone enqueued")

    override suspend fun sync(): GitSyncResult = enqueueRustCycle("Git sync enqueued")

    /**
     * Cycle-record projection: ahead ≈ last plan's remote-bound ops, behind ≈ remote pulls,
     * `lastSyncTime` = durable `last_successful_at_ms`. Counts are the last cycle's observed
     * facts (a fresh diff is a new cycle's job, not a status read).
     */
    override suspend fun getStatus(): GitSyncStatus {
        val status = cycleStatus.refresh()
        return GitSyncStatus(
            hasLocalChanges =
                ((status?.ensurePresentCount ?: 0) + (status?.ensureAbsentCount ?: 0)) > 0,
            aheadCount = (status?.ensurePresentCount ?: 0) + (status?.ensureAbsentCount ?: 0),
            behindCount = status?.pullPresentCount ?: 0,
            lastSyncTime = status?.lastSuccessfulAtMs,
        )
    }

    override suspend fun testConnection(): GitSyncResult {
        val root =
            cycleStatus.workspaceRootPath()
                ?: return GitSyncResult.DirectPathRequired
        val remote = configuration.getRemoteUrl().first()?.trim().orEmpty()
        val branch = configuration.getBranch().first().trim()
        if (remote.isBlank() || branch.isBlank()) {
            return GitSyncResult.NotConfigured
        }
        val authorName = configurationMutation.getAuthorName().first().trim().ifBlank { "Lomo" }
        val authorEmail =
            configurationMutation.getAuthorEmail().first().trim().ifBlank { "git@lomo.local" }
        val provider = CredentialProvider.GIT
        val identity =
            provider.identityField()?.let { secretSupplier.identityUtf8(it.name) }.orEmpty()
        val lease =
            try {
                secretSupplier.issueLease(provider.secretField().name, PROBE_LEASE_TTL_MS)
            } catch (failure: RemoteSyncBoundaryFailure) {
                return failure.toGitResult()
            }
        return try {
            val probe =
                remoteSync.probeBackend(
                    RemoteSyncCycleRequest(
                        workspaceRoot = root,
                        backendKind = "git",
                        endpointUrl = remote,
                        identity = identity,
                        gitBranch = branch,
                        gitAuthorName = authorName,
                        gitAuthorEmail = authorEmail,
                        remoteDatasetId = datasetId("git", remote, ""),
                        secretLeaseId = lease?.leaseId,
                        applyRemote = false,
                    ),
                )
            GitSyncResult.Success(
                "connected: ${probe.listedEntryCount} remote entries" +
                    if (probe.snapshotRevisionPresent) " (snapshot revision present)" else "",
            )
        } catch (failure: RemoteSyncBoundaryFailure) {
            failure.toGitResult()
        } finally {
            lease?.let { revokeLeaseQuietly(it.leaseId) }
        }
    }

    /**
     * Clears the durable sync control tree (session/baseline/tombstones/conflicts/cycle record
     * + Git mirror) under the Rust cycle lock. The next cycle is a real first takeover.
     */
    override suspend fun resetRepository(): GitSyncResult {
        val root =
            cycleStatus.workspaceRootPath()
                ?: return GitSyncResult.DirectPathRequired
        return try {
            remoteSync.resetControlTree(root)
            GitSyncResult.Success("Sync state reset; next sync runs a first takeover")
        } catch (failure: RemoteSyncBoundaryFailure) {
            failure.toGitResult()
        }
    }

    private suspend fun enqueueRustCycle(message: String): GitSyncResult =
        when (
            val outcome =
                rustSyncScheduler.enqueueOneShot(secretFieldKey = CredentialField.GIT_TOKEN.name)
        ) {
            is RustSyncEnqueueOutcome.Accepted -> GitSyncResult.Accepted(message)
            is RustSyncEnqueueOutcome.Rejected ->
                when (outcome.reason) {
                    RustSyncEnqueueRejection.NO_DIRECT_ROOT -> GitSyncResult.DirectPathRequired
                    RustSyncEnqueueRejection.NO_ACTIVE_BACKEND,
                    RustSyncEnqueueRejection.INCOMPLETE_CONFIG,
                    -> GitSyncResult.NotConfigured
                }
        }

    private fun revokeLeaseQuietly(leaseId: String) {
        runCatching { secretSupplier.revokeLease(leaseId) }
        // behavior-contract: silent-result-ok: revoke best-effort; process death drops leases
    }

    private fun RemoteSyncBoundaryFailure.toGitResult(): GitSyncResult =
        GitSyncResult.Error(
            code = GitSyncErrorCode.UNKNOWN,
            message = diagnostic.ifBlank { code },
            exception = this,
        )
}
