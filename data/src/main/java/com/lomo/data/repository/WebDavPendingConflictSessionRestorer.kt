package com.lomo.data.repository

import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.WebDavClient
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import java.nio.charset.StandardCharsets

internal class WebDavPendingConflictSessionRestorer(
    private val runtime: WebDavSyncRepositoryContext,
    private val support: WebDavSyncRepositorySupport,
    private val fileBridge: WebDavSyncFileBridge,
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
                    message = "WebDAV credentials are not configured for pending conflict restore",
                ),
            )
        } else {
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            support.runWebDavIo {
                lifecycleRunner.run(
                    WebDavPendingConflictRestoreStages(
                        validator =
                            WebDavPendingConflictValidator(
                                runtime = runtime,
                                client = support.createClient(config),
                                layout = layout,
                                fileBridge = fileBridge,
                            ),
                        descriptor = descriptor,
                    ),
                )
            }
        }
    }
}

private class WebDavPendingConflictRestoreStages(
    private val validator: WebDavPendingConflictValidator,
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
            backend = SyncBackendType.WEBDAV,
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

private class WebDavPendingConflictValidator(
    private val runtime: WebDavSyncRepositoryContext,
    private val client: WebDavClient,
    private val layout: SyncDirectoryLayout,
    private val fileBridge: WebDavSyncFileBridge,
) {
    private lateinit var meteredClient: WebDavClient

    fun meter(session: RemoteSyncLifecycleSession) {
        meteredClient = session.meter(client)
    }

    suspend fun restore(descriptor: PendingSyncConflictDescriptor): PendingSyncRestoreResult<SyncConflictSet> {
        val restoredFiles = mutableListOf<SyncConflictFile>()
        var invalidation: PendingSyncInvalidationReason? = null
        val iterator = descriptor.files.iterator()
        while (invalidation == null && iterator.hasNext()) {
            when (val restored = restoreFile(iterator.next())) {
                is WebDavPendingConflictFileRestore.Invalidated -> invalidation = restored.reason
                is WebDavPendingConflictFileRestore.Restored -> restoredFiles += restored.file
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

    private suspend fun restoreFile(file: PendingSyncConflictFileDescriptor): WebDavPendingConflictFileRestore {
        val local = fileBridge.localFile(file.relativePath, layout)
        return when {
            local == null -> WebDavPendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.MISSING_LOCAL)
            !file.local.matchesLocal(local) ->
                WebDavPendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
            else -> restoreRemoteFile(file, local)
        }
    }

    private suspend fun restoreRemoteFile(
        file: PendingSyncConflictFileDescriptor,
        local: LocalWebDavFile,
    ): WebDavPendingConflictFileRestore {
        val remote =
            fileBridge.remoteFilesInFolder(
                client = meteredClient,
                folderPath = file.relativePath.substringBeforeLast('/', missingDelimiterValue = ""),
                forceRefresh = true,
            )[file.relativePath]
        return when {
            remote == null -> WebDavPendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.MISSING_REMOTE)
            !file.remote.matchesRemote(remote.etag, remote.lastModified, remote.size) ->
                WebDavPendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            else -> restoreContents(file, local, remote.lastModified)
        }
    }

    private suspend fun restoreContents(
        file: PendingSyncConflictFileDescriptor,
        local: LocalWebDavFile,
        remoteLastModified: Long?,
    ): WebDavPendingConflictFileRestore {
        val localContent =
            if (fileBridge.isMemoPath(file.relativePath, layout)) {
                runtime.markdownStorageDataSource.readFileIn(
                    MemoDirectoryType.MAIN,
                    fileBridge.extractMemoFilename(file.relativePath, layout),
                )
            } else {
                null
            }
        val remoteContent =
            if (fileBridge.isMemoPath(file.relativePath, layout)) {
                String(meteredClient.getSmallFile(file.relativePath).bytes, StandardCharsets.UTF_8)
            } else {
                null
            }
        return when {
            !file.local.matchesContent(localContent) ->
                WebDavPendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
            !file.remote.matchesContent(remoteContent) ->
                WebDavPendingConflictFileRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            else ->
                WebDavPendingConflictFileRestore.Restored(
                    SyncConflictFile(
                        relativePath = file.relativePath,
                        localContent = localContent,
                        remoteContent = remoteContent,
                        isBinary = file.isBinary,
                        localLastModified = local.lastModified,
                        remoteLastModified = remoteLastModified,
                    ),
                )
        }
    }
}

private sealed interface WebDavPendingConflictFileRestore {
    data class Restored(
        val file: SyncConflictFile,
    ) : WebDavPendingConflictFileRestore

    data class Invalidated(
        val reason: PendingSyncInvalidationReason,
    ) : WebDavPendingConflictFileRestore
}
