package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import com.lomo.data.repository.S3SyncWorkIntent

internal suspend fun applyRemoteIndexUpdates(
    remoteIndexStore: S3RemoteIndexStore,
    prepared: PreparedS3Sync,
    execution: S3ActionExecutionResult,
    now: Long,
) {
    if (!remoteIndexStore.remoteIndexEnabled) {
        return
    }
    val currentScanEpoch =
        prepared.remoteReconcileState?.scanEpoch
            ?: prepared.protocolState?.scanEpoch
            ?: 0L
    if (prepared.completeSnapshot) {
        replaceRemoteIndexSnapshot(
            remoteIndexStore = remoteIndexStore,
            snapshotEntries =
                execution.remoteFilesAfterSync.values.map { remoteFile ->
                    remoteFile.toRemoteIndexEntry(
                        now = now,
                        scanEpoch = prepared.remoteReconcileState?.scanEpoch ?: currentScanEpoch,
                    )
                },
        )
        return
    }
    val existingByPath =
        remoteIndexStore.readByRelativePaths(
            prepared.remoteFiles.keys +
                prepared.metadataByPath.keys +
                execution.remoteFilesAfterSync.keys +
                execution.unresolvedPaths +
                execution.failedPaths +
                prepared.observedMissingRemotePaths +
                prepared.remoteReconcileState?.missingRemotePaths.orEmpty(),
        ).associateBy(S3RemoteIndexEntry::relativePath)
    val observedEntries = linkedMapOf<String, S3RemoteIndexEntry>()
    prepared.remoteReconcileState?.observedRemoteEntries?.values?.forEach { entry ->
        observedEntries[entry.relativePath] = entry
    }
    execution.actionOutcomes.keys.forEach { path ->
        execution.remoteFilesAfterSync[path]?.let { remoteFile ->
            observedEntries[path] =
                remoteFile.toRemoteIndexEntry(
                    now = now,
                    scanEpoch = currentScanEpoch,
                    scanPriority = existingByPath[path]?.scanPriority ?: defaultScanPriority(path),
                ).promoteForRecentActivity(
                    now = now,
                    scanEpoch = currentScanEpoch,
                )
        }
    }
    val dirtyEntries =
        (execution.unresolvedPaths + execution.failedPaths).associateWith { path ->
            val existing =
                existingByPath[path]
                    ?: execution.remoteFilesAfterSync[path]?.toRemoteIndexEntry(
                        now = now,
                        scanEpoch = currentScanEpoch,
                    )
                    ?: prepared.remoteFiles[path]?.toRemoteIndexEntry(
                        now = now,
                        scanEpoch = currentScanEpoch,
                    )
            existing
                ?.promoteForDirtyFollowUp(now, currentScanEpoch)
                ?.promoteForRecentCandidate(now, currentScanEpoch)
        }.mapNotNull { (path, entry) -> entry?.let { path to it } }.toMap()
    val missingEntries =
        buildMap {
            prepared.remoteReconcileState?.missingRemotePaths.orEmpty().forEach { path ->
                if (path !in execution.remoteFilesAfterSync) {
                    put(path, buildMissingEntry(path, existingByPath[path], prepared, now, currentScanEpoch))
                }
            }
            prepared.observedMissingRemotePaths.forEach { path ->
                if (path !in execution.remoteFilesAfterSync) {
                    put(path, buildMissingEntry(path, existingByPath[path], prepared, now, currentScanEpoch))
                }
            }
            execution.actionOutcomes.forEach { (path, outcome) ->
                if (outcome.first == S3SyncDirection.DELETE_REMOTE) {
                    put(path, buildMissingEntry(path, existingByPath[path], prepared, now, currentScanEpoch))
                }
            }
        }
    remoteIndexStore.upsert((observedEntries + dirtyEntries + missingEntries).values)
    if (prepared.remoteReconcileState?.completedScanCycle == true) {
        remoteIndexStore.deleteOutsideScanEpoch(prepared.remoteReconcileState.scanEpoch)
    }
}

private suspend fun replaceRemoteIndexSnapshot(
    remoteIndexStore: S3RemoteIndexStore,
    snapshotEntries: Collection<S3RemoteIndexEntry>,
) {
    val snapshotEntriesByPath = snapshotEntries.associateBy(S3RemoteIndexEntry::relativePath)
    val existingEntriesByPath =
        remoteIndexStore
            .readByRelativePaths(snapshotEntriesByPath.keys)
            .associateBy(S3RemoteIndexEntry::relativePath)
    val changedEntries =
        snapshotEntriesByPath
            .mapNotNull { (path, entry) ->
                entry.takeIf { existingEntriesByPath[path] != entry }
            }
    val removedPaths = remoteIndexStore.readAllRelativePaths().toSet() - snapshotEntriesByPath.keys
    remoteIndexStore.upsert(changedEntries)
    remoteIndexStore.deleteByRelativePaths(removedPaths)
}

private fun buildMissingEntry(
    path: String,
    existing: S3RemoteIndexEntry?,
    prepared: PreparedS3Sync,
    now: Long,
    scanEpoch: Long,
): S3RemoteIndexEntry {
    val remotePath = existing?.remotePath ?: prepared.metadataByPath[path]?.remotePath ?: path
    return existing?.markMissingOnScan(now, scanEpoch)
        ?: missingRemoteIndexEntry(
            relativePath = path,
            remotePath = remotePath,
            now = now,
            scanEpoch = scanEpoch,
            previousPriority = prepared.remoteFiles[path]?.let { remote -> defaultScanPriority(remote.path) },
        )
}

internal fun shouldRunIncrementalReconcile(
    policy: S3SyncWorkIntent,
    config: S3ResolvedConfig,
    protocolState: S3SyncProtocolState,
    now: Long = System.currentTimeMillis(),
): Boolean =
    policy != S3SyncWorkIntent.FAST_ONLY &&
        (
            protocolState.remoteScanCursor != null ||
                protocolState.lastReconcileAt == null ||
                now - protocolState.lastReconcileAt >= config.endpointProfile.incrementalReconcileIntervalMs
        )

internal fun nextScanEpoch(
    protocolState: S3SyncProtocolState?,
    now: Long = System.currentTimeMillis(),
): Long = maxOf(now, (protocolState?.scanEpoch ?: 0L) + 1L)

internal suspend fun promoteDirtyRemoteCandidates(
    remoteIndexStore: S3RemoteIndexStore,
    relativePaths: Collection<String>,
    now: Long = System.currentTimeMillis(),
) {
    if (!remoteIndexStore.remoteIndexEnabled || relativePaths.isEmpty()) {
        return
    }
    val existingEntries = remoteIndexStore.readByRelativePaths(relativePaths)
    if (existingEntries.isEmpty()) {
        return
    }
    remoteIndexStore.upsert(existingEntries.map { entry -> entry.promoteForRecentCandidate(now) })
}
