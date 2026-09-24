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
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncStatus
import com.lomo.domain.model.identityField
import com.lomo.domain.model.secretField
import com.lomo.domain.repository.S3SyncConfigurationMutationRepository
import com.lomo.domain.repository.S3SyncConfigurationRepository
import com.lomo.domain.repository.S3SyncRepository
import com.lomo.domain.repository.S3SyncStateRepository
import kotlinx.coroutines.flow.first

private const val PROBE_LEASE_TTL_MS: Long = 30_000

/**
 * Post P5-13 production S3 facade: config/credentials + Rust sync.
 *
 * `sync` returns **Accepted** (WorkManager admission); `getStatus`/`syncState` read the durable
 * `cycle_state.rec`; `testConnection` runs a real `sync_probe_backend` round-trip. Kotlin-side
 * conflict/review resolution is deleted — Sync Center / Sync Inbox own those surfaces.
 */
class S3RemoteSyncFacade(
    private val configuration: S3SyncConfigurationRepository,
    private val configurationMutation: S3SyncConfigurationMutationRepository,
    private val state: S3SyncStateRepository,
    private val rustSyncScheduler: RustSyncScheduler,
    private val remoteSync: RemoteSyncRepository,
    private val secretSupplier: RustSyncSecretSupplier,
    private val cycleStatus: RustSyncCycleStatusStore,
) : S3SyncRepository,
    S3SyncConfigurationRepository by configuration,
    S3SyncConfigurationMutationRepository by configurationMutation,
    S3SyncStateRepository by state {
    override suspend fun sync(): S3SyncResult = enqueueRustCycle("S3 sync enqueued")

    /** Cycle-record projection: remote/local listing sizes + pending delta + last success. */
    override suspend fun getStatus(): S3SyncStatus {
        val status = cycleStatus.refresh()
        return S3SyncStatus(
            remoteFileCount = status?.remoteListedCount ?: 0,
            localFileCount = status?.localEntryCount ?: 0,
            pendingChanges =
                (status?.ensurePresentCount ?: 0) +
                    (status?.ensureAbsentCount ?: 0) +
                    (status?.pullPresentCount ?: 0),
            lastSyncTime = status?.lastSuccessfulAtMs,
        )
    }

    override suspend fun testConnection(): S3SyncResult {
        val root =
            cycleStatus.workspaceRootPath()
                ?: return S3SyncResult.Error(
                    code = S3SyncErrorCode.UNKNOWN,
                    message = "S3 sync requires a direct local directory path",
                )
        val endpoint = configuration.getEndpointUrl().first()?.trim().orEmpty()
        val region = configuration.getRegion().first()?.trim().orEmpty()
        val bucket = configuration.getBucket().first()?.trim().orEmpty()
        if (endpoint.isBlank() || region.isBlank() || bucket.isBlank()) {
            return S3SyncResult.NotConfigured
        }
        val provider = CredentialProvider.S3
        val identity =
            provider.identityField()?.let { secretSupplier.identityUtf8(it.name) }.orEmpty()
        val lease =
            try {
                secretSupplier.issueLease(provider.secretField().name, PROBE_LEASE_TTL_MS)
            } catch (failure: RemoteSyncBoundaryFailure) {
                return failure.toS3Result()
            } ?: return S3SyncResult.NotConfigured
        return try {
            val probe =
                remoteSync.probeBackend(
                    RemoteSyncCycleRequest(
                        workspaceRoot = root,
                        backendKind = "s3",
                        endpointUrl = endpoint,
                        identity = identity,
                        s3Bucket = bucket,
                        s3Prefix = configuration.getPrefix().first()?.trim().orEmpty(),
                        s3Region = region,
                        remoteDatasetId = datasetId("s3", endpoint, bucket),
                        secretLeaseId = lease.leaseId,
                        applyRemote = false,
                    ),
                )
            S3SyncResult.Success("connected: ${probe.listedEntryCount} remote entries")
        } catch (failure: RemoteSyncBoundaryFailure) {
            failure.toS3Result()
        } finally {
            revokeLeaseQuietly(lease.leaseId)
        }
    }

    private suspend fun enqueueRustCycle(message: String): S3SyncResult =
        when (
            val outcome =
                rustSyncScheduler.enqueueOneShot(
                    secretFieldKey = CredentialField.S3_SECRET_ACCESS_KEY.name,
                )
        ) {
            is RustSyncEnqueueOutcome.Accepted -> S3SyncResult.Accepted(message)
            is RustSyncEnqueueOutcome.Rejected ->
                when (outcome.reason) {
                    RustSyncEnqueueRejection.NO_DIRECT_ROOT ->
                        S3SyncResult.Error(
                            code = S3SyncErrorCode.UNKNOWN,
                            message = "S3 sync requires a direct local directory path",
                        )
                    RustSyncEnqueueRejection.NO_ACTIVE_BACKEND,
                    RustSyncEnqueueRejection.INCOMPLETE_CONFIG,
                    -> S3SyncResult.NotConfigured
                }
        }

    private fun revokeLeaseQuietly(leaseId: String) {
        runCatching { secretSupplier.revokeLease(leaseId) }
        // behavior-contract: silent-result-ok: revoke best-effort; process death drops leases
    }

    private fun RemoteSyncBoundaryFailure.toS3Result(): S3SyncResult =
        S3SyncResult.Error(
            code = S3SyncErrorCode.UNKNOWN,
            message = diagnostic.ifBlank { code },
            exception = this,
        )
}
