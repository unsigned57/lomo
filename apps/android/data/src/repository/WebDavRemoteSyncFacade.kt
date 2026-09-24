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
import com.lomo.domain.model.WebDavSyncErrorCode
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncStatus
import com.lomo.domain.model.identityField
import com.lomo.domain.model.secretField
import com.lomo.domain.repository.WebDavSyncConfigurationMutationRepository
import com.lomo.domain.repository.WebDavSyncConfigurationRepository
import com.lomo.domain.repository.WebDavSyncRepository
import com.lomo.domain.repository.WebDavSyncStateRepository
import kotlinx.coroutines.flow.first

private const val PROBE_LEASE_TTL_MS: Long = 30_000

/**
 * Post P5-13 production WebDAV facade: config/credentials + Rust sync.
 *
 * `sync` returns **Accepted** (WorkManager admission); `getStatus`/`syncState` read the durable
 * `cycle_state.rec`; `testConnection` runs a real `sync_probe_backend` round-trip. Kotlin-side
 * conflict/review resolution is deleted — Sync Center / Sync Inbox own those surfaces.
 */
class WebDavRemoteSyncFacade(
    private val configuration: WebDavSyncConfigurationRepository,
    private val configurationMutation: WebDavSyncConfigurationMutationRepository,
    private val state: WebDavSyncStateRepository,
    private val rustSyncScheduler: RustSyncScheduler,
    private val remoteSync: RemoteSyncRepository,
    private val secretSupplier: RustSyncSecretSupplier,
    private val cycleStatus: RustSyncCycleStatusStore,
) : WebDavSyncRepository,
    WebDavSyncConfigurationRepository by configuration,
    WebDavSyncConfigurationMutationRepository by configurationMutation,
    WebDavSyncStateRepository by state {
    override suspend fun sync(): WebDavSyncResult = enqueueRustCycle("WebDAV sync enqueued")

    /** Cycle-record projection: remote/local listing sizes + pending delta + last success. */
    override suspend fun getStatus(): WebDavSyncStatus {
        val status = cycleStatus.refresh()
        return WebDavSyncStatus(
            remoteFileCount = status?.remoteListedCount ?: 0,
            localFileCount = status?.localEntryCount ?: 0,
            pendingChanges =
                (status?.ensurePresentCount ?: 0) +
                    (status?.ensureAbsentCount ?: 0) +
                    (status?.pullPresentCount ?: 0),
            lastSyncTime = status?.lastSuccessfulAtMs,
        )
    }

    override suspend fun testConnection(): WebDavSyncResult {
        val root =
            cycleStatus.workspaceRootPath()
                ?: return WebDavSyncResult.Error(
                    code = WebDavSyncErrorCode.UNKNOWN,
                    message = "WebDAV sync requires a direct local directory path",
                )
        val endpoint =
            configuration.getEndpointUrl().first()?.trim().orEmpty().ifBlank {
                configuration.getBaseUrl().first()?.trim().orEmpty()
            }
        if (endpoint.isBlank()) {
            return WebDavSyncResult.NotConfigured
        }
        val provider = CredentialProvider.WEBDAV
        val identity =
            provider.identityField()?.let { secretSupplier.identityUtf8(it.name) }.orEmpty()
        val lease =
            try {
                secretSupplier.issueLease(provider.secretField().name, PROBE_LEASE_TTL_MS)
            } catch (failure: RemoteSyncBoundaryFailure) {
                return failure.toWebDavResult()
            } ?: return WebDavSyncResult.NotConfigured
        return try {
            val probe =
                remoteSync.probeBackend(
                    RemoteSyncCycleRequest(
                        workspaceRoot = root,
                        backendKind = "webdav",
                        endpointUrl = endpoint,
                        identity = identity,
                        remoteDatasetId = datasetId("webdav", endpoint, ""),
                        secretLeaseId = lease.leaseId,
                        applyRemote = false,
                    ),
                )
            WebDavSyncResult.Success("connected: ${probe.listedEntryCount} remote entries")
        } catch (failure: RemoteSyncBoundaryFailure) {
            failure.toWebDavResult()
        } finally {
            revokeLeaseQuietly(lease.leaseId)
        }
    }

    private suspend fun enqueueRustCycle(message: String): WebDavSyncResult =
        when (
            val outcome =
                rustSyncScheduler.enqueueOneShot(
                    secretFieldKey = CredentialField.WEBDAV_PASSWORD.name,
                )
        ) {
            is RustSyncEnqueueOutcome.Accepted -> WebDavSyncResult.Accepted(message)
            is RustSyncEnqueueOutcome.Rejected ->
                when (outcome.reason) {
                    RustSyncEnqueueRejection.NO_DIRECT_ROOT ->
                        WebDavSyncResult.Error(
                            code = WebDavSyncErrorCode.UNKNOWN,
                            message = "WebDAV sync requires a direct local directory path",
                        )
                    RustSyncEnqueueRejection.NO_ACTIVE_BACKEND,
                    RustSyncEnqueueRejection.INCOMPLETE_CONFIG,
                    -> WebDavSyncResult.NotConfigured
                }
        }

    private fun revokeLeaseQuietly(leaseId: String) {
        runCatching { secretSupplier.revokeLease(leaseId) }
        // behavior-contract: silent-result-ok: revoke best-effort; process death drops leases
    }

    private fun RemoteSyncBoundaryFailure.toWebDavResult(): WebDavSyncResult =
        WebDavSyncResult.Error(
            code = WebDavSyncErrorCode.UNKNOWN,
            message = diagnostic.ifBlank { code },
            exception = this,
        )
}
