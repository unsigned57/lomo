package com.lomo.data.repository

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSessionKind
import com.lomo.domain.model.SyncConflictSet
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal suspend fun buildS3ConflictSet(
    actions: List<S3SyncAction>,
    client: com.lomo.data.s3.LomoS3Client,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    config: S3ResolvedConfig,
    remoteFiles: Map<String, RemoteS3File>,
    fileBridgeScope: S3SyncFileBridgeScope,
    mode: S3LocalSyncMode,
    encodingSupport: S3SyncEncodingSupport,
    sessionKind: SyncConflictSessionKind = SyncConflictSessionKind.STANDARD_CONFLICT,
    lightweightPreview: Boolean = false,
): SyncConflictSet? {
    val concurrencyLimiter = Semaphore(S3_ACTION_CONCURRENCY)
    val conflictFiles =
        coroutineScope {
            actions.map { action ->
                async {
                    concurrencyLimiter.withPermit {
                        if (lightweightPreview) {
                            return@withPermit SyncConflictFile(
                                relativePath = action.path,
                                localContent = null,
                                remoteContent = null,
                                isBinary = !action.path.endsWith(S3_MEMO_SUFFIX),
                            )
                        }
                        val localContent =
                            if (isMemoPath(action.path, layout, mode)) {
                                fileBridgeScope.readLocalText(action.path, layout)
                            } else {
                                null
                            }
                        val remoteContent =
                            runNonFatalCatching {
                                val remotePath = remoteFiles[action.path]?.remotePath ?: return@withPermit null
                                val payload = client.getObject(remotePath)
                                String(
                                    encodingSupport.decodeContent(payload.bytes, config),
                                    StandardCharsets.UTF_8,
                                )
                            }.getOrNull()
                        SyncConflictFile(
                            relativePath = action.path,
                            localContent = localContent,
                            remoteContent = remoteContent,
                            isBinary = !action.path.endsWith(S3_MEMO_SUFFIX),
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
    return conflictFiles
        .takeIf(List<SyncConflictFile>::isNotEmpty)
        ?.let { files ->
            SyncConflictSet(
                source = SyncBackendType.S3,
                files = files,
                timestamp = System.currentTimeMillis(),
                sessionKind = sessionKind,
            )
        }
}

internal suspend fun finalizeAfterS3Sync(
    runtime: S3SyncRepositoryContext,
    result: S3SyncResult,
    execution: S3ActionExecutionResult?,
): S3SyncResult {
    if (!result.shouldFinalizeAfterSync()) {
        return result
    }
    return runNonFatalCatching {
        when (val memoRefreshPlan = execution?.memoRefreshPlan ?: S3MemoRefreshPlan.None) {
            S3MemoRefreshPlan.None -> Unit
            S3MemoRefreshPlan.Full -> runtime.memoSynchronizer.refreshImportedSync()
            is S3MemoRefreshPlan.Targets ->
                memoRefreshPlan.filenames
                    .sorted()
                    .forEach { targetFilename ->
                        runtime.memoSynchronizer.refreshImportedSync(targetFilename)
                    }
        }
        val now = System.currentTimeMillis()
        runtime.dataStore.updateS3LastSyncTime(now)
        runtime.stateHolder.state.value = result.stateAfterRefresh(now)
        result
    }.getOrElse { error ->
        val message =
            "S3 sync completed but memo refresh failed: " +
                "${error.message ?: S3_UNKNOWN_ERROR_MESSAGE}"
        runtime.stateHolder.state.value = S3SyncState.Error(message, System.currentTimeMillis())
        S3SyncResult.Error(message, error, result.outcomesForRefreshFailure())
    }
}

internal suspend fun commitIncrementalS3StateIfNeeded(
    protocolStateStore: S3SyncProtocolStateStore,
    localChangeJournalStore: S3LocalChangeJournalStore,
    remoteIndexStore: S3RemoteIndexStore,
    recentActivityTracker: S3RemoteRecentActivityTracker,
    prepared: PreparedS3Sync,
    execution: S3ActionExecutionResult,
    result: S3SyncResult,
    now: Long,
) {
    if (!protocolStateStore.incrementalSyncEnabled || !localChangeJournalStore.incrementalSyncEnabled) {
        return
    }
    if (prepared.conflictSet != null) {
        recordConflictRemoteCandidatesIfNeeded(
            recentActivityTracker = recentActivityTracker,
            remoteIndexStore = remoteIndexStore,
            prepared = prepared,
            now = now,
        )
        return
    }
    if (!result.shouldFinalizeAfterSync()) {
        return
    }

    applyRemoteIndexUpdates(
        remoteIndexStore = remoteIndexStore,
        prepared = prepared,
        execution = execution,
        now = now,
    )
    val previousState = prepared.protocolState
    val indexedCounts = computeIndexedCounts(prepared, execution)
    val indexedRemoteCount =
        if (remoteIndexStore.remoteIndexEnabled) {
            remoteIndexStore.readPresentCount()
        } else {
            indexedCounts.second
        }
    val remoteReconcileState = prepared.remoteReconcileState
    protocolStateStore.write(
        S3SyncProtocolState(
            protocolVersion = S3_INCREMENTAL_PROTOCOL_VERSION,
            lastSuccessfulSyncAt = now,
            lastFastSyncAt = now,
            lastReconcileAt =
                if (prepared.completeSnapshot || remoteReconcileState != null) {
                    now
                } else {
                    previousState?.lastReconcileAt
                },
            lastFullRemoteScanAt =
                when {
                    prepared.completeSnapshot -> now
                    remoteReconcileState?.completedScanCycle == true -> now
                    else -> previousState?.lastFullRemoteScanAt
                },
            indexedLocalFileCount = indexedCounts.first,
            indexedRemoteFileCount = indexedRemoteCount,
            localModeFingerprint = prepared.localModeFingerprint,
            remoteScanCursor =
                when {
                    prepared.completeSnapshot -> null
                    remoteReconcileState != null -> remoteReconcileState.nextScanCursor
                    else -> previousState?.remoteScanCursor
                },
            scanEpoch =
                when {
                    prepared.completeSnapshot ->
                        prepared.remoteReconcileState?.scanEpoch
                            ?: previousState?.scanEpoch
                            ?: 0L
                    remoteReconcileState != null -> remoteReconcileState.scanEpoch
                    else -> previousState?.scanEpoch ?: 0L
                },
        ),
    )

    val retainedJournalPaths = execution.unresolvedPaths + execution.failedPaths
    val clearableJournalIds =
        prepared.clearableJournalIds.filter { id ->
            val path = prepared.journalPathsById[id] ?: return@filter false
            path !in retainedJournalPaths
        }
    localChangeJournalStore.remove(clearableJournalIds)
}

private suspend fun recordConflictRemoteCandidatesIfNeeded(
    recentActivityTracker: S3RemoteRecentActivityTracker,
    remoteIndexStore: S3RemoteIndexStore,
    prepared: PreparedS3Sync,
    now: Long,
) {
    if (!remoteIndexStore.remoteIndexEnabled) {
        return
    }
    val conflictPaths =
        prepared.plan.actions
            .asSequence()
            .filter { action -> action.direction == S3SyncDirection.CONFLICT }
            .map(S3SyncAction::path)
            .toSet()
    if (conflictPaths.isEmpty()) {
        return
    }
    recentActivityTracker.recordRetryCandidates(
        remoteIndexStore = remoteIndexStore,
        relativePaths = conflictPaths,
        now = now,
        scanEpoch = prepared.protocolState?.scanEpoch ?: 0L,
    )
    val existingByPath =
        remoteIndexStore.readByRelativePaths(conflictPaths).associateBy(S3RemoteIndexEntry::relativePath)
    remoteIndexStore.upsert(
        conflictPaths.mapNotNull { path ->
            existingByPath[path]
                ?.promoteForDirtyFollowUp(now, prepared.protocolState?.scanEpoch ?: 0L)
                ?.promoteForRecentCandidate(now, prepared.protocolState?.scanEpoch ?: 0L)
                ?: prepared.remoteFiles[path]?.toRemoteIndexEntry(
                    now = now,
                    scanEpoch = prepared.protocolState?.scanEpoch ?: 0L,
                    scanPriority = defaultScanPriority(path),
                )?.promoteForDirtyFollowUp(now, prepared.protocolState?.scanEpoch ?: 0L)
                    ?.promoteForRecentCandidate(now, prepared.protocolState?.scanEpoch ?: 0L)
        },
    )
}

internal fun buildS3SyncResult(
    prepared: PreparedS3Sync,
    execution: S3ActionExecutionResult,
): S3SyncResult =
    when {
        execution.failedPaths.isNotEmpty() -> {
            val summary =
                "S3 sync partially failed: ${execution.failedPaths.size} " +
                    "file(s) failed: ${execution.failedPaths.joinToString()}"
            S3SyncResult.Error(
                message = summary,
                outcomes = prepared.plan.actions.map(S3SyncAction::toOutcome),
            )
        }

        prepared.conflictSet != null ->
            S3SyncResult.Conflict(
                message = "${prepared.conflictSet.files.size} conflicting file(s) detected",
                conflicts = prepared.conflictSet,
            )

        prepared.plan.actions.isEmpty() ->
            S3SyncResult.Success(
                message = "S3 already up to date",
                outcomes = prepared.plan.actions.map(S3SyncAction::toOutcome),
            )

        else ->
            S3SyncResult.Success(
                message = "S3 sync completed",
                outcomes = prepared.plan.actions.map(S3SyncAction::toOutcome),
            )
    }

internal const val S3_ACTION_CONCURRENCY = 4
