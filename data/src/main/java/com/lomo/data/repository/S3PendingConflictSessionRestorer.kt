package com.lomo.data.repository

import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet

internal class S3PendingConflictSessionRestorer(
    private val runtime: S3SyncRepositoryContext,
    private val support: S3SyncRepositorySupport,
    private val encodingSupport: S3SyncEncodingSupport,
    private val fileBridge: S3SyncFileBridge,
    private val lifecycleRunner: RemoteSyncLifecycleRunner,
) : PendingSyncConflictRestorer {
    override suspend fun restore(
        descriptor: PendingSyncConflictDescriptor,
    ): PendingSyncRestoreResult<SyncConflictSet> {
        val config = support.resolveConfig()
        return if (config == null) {
            PendingSyncRestoreResult.Failed(
                PendingSyncRestoreError(
                    category = PendingSyncRestoreErrorCategory.CREDENTIAL_FAILED,
                    message = "S3 credentials are not configured for pending conflict restore",
                ),
            )
        } else {
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val mode = resolveLocalSyncMode(runtime)
            val fileBridgeScope = fileBridge.modeAware(mode)
            support.withClient(config) { client ->
                lifecycleRunner.run(
                    S3PendingConflictRestoreStages(
                        validator =
                            S3PendingConflictValidator(
                                config = config,
                                client = client,
                                layout = layout,
                                fileBridgeScope = fileBridgeScope,
                                encodingSupport = encodingSupport,
                            ),
                        descriptor = descriptor,
                    ),
                )
            }
        }
    }
}

private class S3PendingConflictRestoreStages(
    private val validator: S3PendingConflictValidator,
    private val descriptor: PendingSyncConflictDescriptor,
) : RemoteSyncLifecycleStages<
        PendingSyncConflictDescriptor,
        PendingSyncConflictDescriptor,
        PendingSyncConflictDescriptor,
        Unit,
        PendingSyncRestoreResult<SyncConflictSet>,
        PendingSyncRestoreResult<SyncConflictSet>,
        PendingSyncRestoreResult<SyncConflictSet>,
        PendingSyncRestoreResult<SyncConflictSet>,
    > {
    override val context: RemoteSyncLifecycleContext =
        RemoteSyncLifecycleContext(
            backend = SyncBackendType.S3,
            budget = RemoteSyncBudgetPolicy.Limited(DEFAULT_REMOTE_SYNC_NETWORK_OPERATION_BUDGET),
        )

    override suspend fun loadSnapshot(session: RemoteSyncLifecycleSession): PendingSyncConflictDescriptor {
        validator.meter(session)
        return descriptor
    }

    override suspend fun plan(
        snapshot: PendingSyncConflictDescriptor,
        session: RemoteSyncLifecycleSession,
    ): PendingSyncConflictDescriptor = snapshot

    override suspend fun verify(
        plan: PendingSyncConflictDescriptor,
        session: RemoteSyncLifecycleSession,
    ): PendingSyncConflictDescriptor = plan

    override suspend fun materializeConflicts(
        verified: PendingSyncConflictDescriptor,
        session: RemoteSyncLifecycleSession,
    ) = Unit

    override suspend fun apply(
        verified: PendingSyncConflictDescriptor,
        conflicts: Unit,
        session: RemoteSyncLifecycleSession,
    ): PendingSyncRestoreResult<SyncConflictSet> = validator.restore(verified)

    override suspend fun commitMetadata(
        verified: PendingSyncConflictDescriptor,
        conflicts: Unit,
        applied: PendingSyncRestoreResult<SyncConflictSet>,
        session: RemoteSyncLifecycleSession,
    ): PendingSyncRestoreResult<SyncConflictSet> = applied

    override suspend fun finalize(
        verified: PendingSyncConflictDescriptor,
        conflicts: Unit,
        applied: PendingSyncRestoreResult<SyncConflictSet>,
        metadata: PendingSyncRestoreResult<SyncConflictSet>,
        session: RemoteSyncLifecycleSession,
    ): PendingSyncRestoreResult<SyncConflictSet> = metadata

    override fun summarizeSnapshot(snapshot: PendingSyncConflictDescriptor): RemoteSyncSnapshotTelemetry =
        RemoteSyncSnapshotTelemetry(
            localFileCount = snapshot.files.size,
            remoteFileCount = snapshot.files.size,
            metadataEntryCount = snapshot.files.size,
        )

    override fun summarizePlan(plan: PendingSyncConflictDescriptor): RemoteSyncActionTelemetry =
        RemoteSyncActionTelemetry(total = plan.files.size, conflict = plan.files.size)

    override fun summarizeVerification(verified: PendingSyncConflictDescriptor): RemoteSyncActionTelemetry =
        RemoteSyncActionTelemetry(total = verified.files.size, conflict = verified.files.size)

    override fun summarizeRefresh(
        finalized: PendingSyncRestoreResult<SyncConflictSet>,
    ): RemoteSyncRefreshTelemetry =
        RemoteSyncRefreshTelemetry(durationMillis = 0)

    override fun mapResult(
        finalized: PendingSyncRestoreResult<SyncConflictSet>,
    ): PendingSyncRestoreResult<SyncConflictSet> = finalized

    override fun mapError(error: Throwable): PendingSyncRestoreResult<SyncConflictSet> =
        PendingSyncRestoreResult.Failed(error.toPendingSyncRestoreError())

    override suspend fun release() = Unit
}

private class S3PendingConflictValidator(
    private val config: S3ResolvedConfig,
    private val client: LomoS3Client,
    private val layout: SyncDirectoryLayout,
    private val fileBridgeScope: S3SyncFileBridgeScope,
    private val encodingSupport: S3SyncEncodingSupport,
) {
    private lateinit var meteredClient: LomoS3Client

    fun meter(session: RemoteSyncLifecycleSession) {
        meteredClient = session.meter(client)
    }

    suspend fun restore(descriptor: PendingSyncConflictDescriptor): PendingSyncRestoreResult<SyncConflictSet> {
        val restoredFiles = mutableListOf<SyncConflictFile>()
        var invalidation: PendingSyncInvalidationReason? = null
        val iterator = descriptor.files.iterator()
        while (invalidation == null && iterator.hasNext()) {
            when (val restored = restoreFile(iterator.next())) {
                is PendingConflictFileRestore.Invalidated -> invalidation = restored.reason
                is PendingConflictFileRestore.Restored -> restoredFiles += restored.file
            }
        }
        return invalidation?.let { reason -> PendingSyncRestoreResult.Invalidated(reason) }
            ?: PendingSyncRestoreResult.Restored(
                SyncConflictSet(
                    source = descriptor.source,
                    files = restoredFiles,
                    timestamp = descriptor.timestamp,
                ),
            )
    }

    private suspend fun restoreFile(file: PendingSyncConflictFileDescriptor): PendingConflictFileRestore {
        val local = fileBridgeScope.localFile(file.relativePath, layout)
        return when {
            local == null -> PendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.MISSING_LOCAL)
            !file.local.matchesLocal(local) ->
                PendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
            else -> restoreRemoteFile(file, local)
        }
    }

    private suspend fun restoreRemoteFile(
        file: PendingSyncConflictFileDescriptor,
        local: LocalS3File,
    ): PendingConflictFileRestore {
        val remotePath = remotePathFor(file)
        val remote = meteredClient.getObjectMetadata(remotePath)
        return when {
            remote == null -> PendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.MISSING_REMOTE)
            !file.remote.matchesRemoteMetadata(remote, encodingSupport) ->
                PendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            else -> restoreContents(file, local, remotePath, remote)
        }
    }

    private suspend fun restoreContents(
        file: PendingSyncConflictFileDescriptor,
        local: LocalS3File,
        remotePath: String,
        remote: S3RemoteObject,
    ): PendingConflictFileRestore {
        val localContent =
            if (file.isBinary) {
                null
            } else {
                fileBridgeScope.readLocalText(file.relativePath, layout)
            }
        val remoteContent =
            if (file.isBinary) {
                null
            } else {
                String(
                    encodingSupport.decodeContent(meteredClient.getSmallObject(remotePath).bytes, config),
                    Charsets.UTF_8,
                )
            }
        return when {
            !file.local.matchesContent(localContent) ->
                PendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
            !file.remote.matchesContent(remoteContent) ->
                PendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            else ->
                PendingConflictFileRestore.Restored(
                    SyncConflictFile(
                        relativePath = file.relativePath,
                        localContent = localContent,
                        remoteContent = remoteContent,
                        isBinary = file.isBinary,
                        localLastModified = local.lastModified,
                        remoteLastModified = encodingSupport.resolveRemoteLastModified(
                            remote.metadata,
                            remote.lastModified,
                        ),
                    ),
                )
        }
    }

    private fun remotePathFor(file: PendingSyncConflictFileDescriptor): String =
        file.remote.locator.takeIf { locator -> locator != file.relativePath }
            ?: encodingSupport.remotePathFor(file.relativePath, config)
}

private fun PendingSyncSideMetadata.matchesRemoteMetadata(
    remote: S3RemoteObject,
    encodingSupport: S3SyncEncodingSupport,
): Boolean =
    matchesRemote(
        actualEtag = remote.eTag,
        actualLastModified = encodingSupport.resolveRemoteLastModified(remote.metadata, remote.lastModified),
        actualSize = remote.size,
    )

private sealed interface PendingConflictFileRestore {
    data class Restored(
        val file: SyncConflictFile,
    ) : PendingConflictFileRestore

    data class Invalidated(
        val reason: PendingSyncInvalidationReason,
    ) : PendingConflictFileRestore
}
