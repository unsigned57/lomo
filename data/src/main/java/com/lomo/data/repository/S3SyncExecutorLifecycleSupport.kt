package com.lomo.data.repository

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class S3PendingConflictMaterialization(
    val conflictSet: SyncConflictSet,
    val descriptor: PendingSyncConflictDescriptor,
)

internal data class S3PendingReviewMaterialization(
    val reviewSession: SyncReviewSession,
    val descriptor: PendingSyncReviewDescriptor,
)

internal suspend fun buildS3PendingConflictMaterialization(
    actions: List<S3SyncAction>,
    client: com.lomo.data.s3.LomoS3Client,
    layout: com.lomo.data.sync.SyncDirectoryLayout,
    config: S3ResolvedConfig,
    remoteFiles: Map<String, RemoteS3File>,
    fileBridgeScope: S3SyncFileBridgeScope,
    mode: S3LocalSyncMode,
    encodingSupport: S3SyncEncodingSupport,
    objectKeyPolicy: S3RemoteObjectKeyPolicy,
    actionConcurrency: Int = S3_ACTION_CONCURRENCY,
    lightweightPreview: Boolean = false,
): S3PendingConflictMaterialization? {
    val concurrencyLimiter = Semaphore(actionConcurrency.coercePositiveConcurrency())
    val materializedFiles =
        coroutineScope {
            actions.map { action ->
                async {
                    concurrencyLimiter.withPermit {
                        if (lightweightPreview) {
                            val localFile =
                                fileBridgeScope.pendingValidationLocalFile(
                                    path = action.path,
                                    layout = layout,
                                    requireContentFingerprint = true,
                                )
                            val remoteFile = remoteFiles[action.path]
                            remoteFile?.let { file -> objectKeyPolicy.validatedExistingKey(file.remotePath, config) }
                            return@withPermit buildS3PendingConflictFileMaterialization(
                                path = action.path,
                                localFile = localFile,
                                remoteFile = remoteFile,
                                localContent = null,
                                remoteContent = null,
                            )
                        }
                        val localFile =
                            fileBridgeScope.pendingValidationLocalFile(
                                path = action.path,
                                layout = layout,
                                requireContentFingerprint = true,
                            )
                        val remoteFile = remoteFiles[action.path]
                        val remoteKey =
                            remoteFile?.let { file ->
                                objectKeyPolicy.validatedExistingKey(file.remotePath, config)
                            }
                        val localContent =
                            if (isMemoPath(action.path, layout, mode)) {
                                fileBridgeScope.readLocalText(action.path, layout)
                            } else {
                                null
                            }
                        val remoteContent =
                            if (isMemoPath(action.path, layout, mode)) {
                                val key = remoteKey ?: return@withPermit null
                                runNonFatalCatching {
                                    val payload = client.getSmallObject(key.value)
                                    String(
                                        encodingSupport.decodeContent(payload.bytes, config),
                                        StandardCharsets.UTF_8,
                                    )
                                }.getOrNull()
                            } else {
                                null
                            }
                        buildS3PendingConflictFileMaterialization(
                            path = action.path,
                            localFile = localFile,
                            remoteFile = remoteFile,
                            localContent = localContent,
                            remoteContent = remoteContent,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
    if (materializedFiles.isEmpty()) return null
    val timestamp = System.currentTimeMillis()
    return S3PendingConflictMaterialization(
        conflictSet =
            SyncConflictSet(
                source = SyncBackendType.S3,
                files = materializedFiles.map(S3PendingConflictFileMaterialization::file),
                timestamp = timestamp,
            ),
        descriptor =
            PendingSyncConflictDescriptor(
                source = SyncBackendType.S3,
                workspaceGeneration = "",
                files = materializedFiles.map(S3PendingConflictFileMaterialization::descriptor),
                timestamp = timestamp,
                validationStatus = PendingSyncValidationStatus.PENDING_RELOAD,
            ),
    )
}

internal fun S3PendingConflictMaterialization.toInitialImportReviewMaterialization(): S3PendingReviewMaterialization =
    S3PendingReviewMaterialization(
        reviewSession =
            SyncReviewSession(
                source = conflictSet.source,
                items =
                    conflictSet.files.map { file ->
                        SyncReviewItem(
                            relativePath = file.relativePath,
                            localContent = file.localContent,
                            incomingContent = file.remoteContent,
                            isBinary = file.isBinary,
                            localLastModified = file.localLastModified,
                            incomingLastModified = file.remoteLastModified,
                        )
                    },
                timestamp = conflictSet.timestamp,
                kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
            ),
        descriptor =
            PendingSyncReviewDescriptor(
                source = descriptor.source,
                workspaceGeneration = descriptor.workspaceGeneration,
                kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
                items =
                    descriptor.files.map { file ->
                        PendingSyncReviewItemDescriptor(
                            relativePath = file.relativePath,
                            isBinary = file.isBinary,
                            local = file.local,
                            incoming = file.remote,
                            state = SyncReviewItemState.CONTENT_DIFFERENCE,
                        )
                    },
                timestamp = descriptor.timestamp,
                validationStatus = descriptor.validationStatus,
            ),
    )

private data class S3PendingConflictFileMaterialization(
    val file: SyncConflictFile,
    val descriptor: PendingSyncConflictFileDescriptor,
)

private fun buildS3PendingConflictFileMaterialization(
    path: String,
    localFile: LocalS3File?,
    remoteFile: RemoteS3File?,
    localContent: String?,
    remoteContent: String?,
): S3PendingConflictFileMaterialization {
    val isBinary = !path.endsWith(S3_MEMO_SUFFIX)
    val localContentHash = localContent?.pendingContentHash()
    val remoteContentHash = remoteContent?.pendingContentHash()
    val discoveredRemoteFile =
        requireNotNull(remoteFile) {
            "S3 pending materialization requires a discovered remote locator for $path"
        }
    return S3PendingConflictFileMaterialization(
        file =
            SyncConflictFile(
                relativePath = path,
                localContent = localContent,
                remoteContent = remoteContent,
                isBinary = isBinary,
                localLastModified = localFile?.lastModified,
                remoteLastModified = discoveredRemoteFile.lastModified,
            ),
        descriptor =
            PendingSyncConflictFileDescriptor(
                relativePath = path,
                isBinary = isBinary,
                local =
                    PendingSyncSideMetadata(
                        locator = path,
                        contentHash = localFile?.localFingerprint ?: localContentHash,
                        lastModified = localFile?.lastModified,
                        size = localFile?.size ?: localContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                        etag = localFile?.localFingerprint ?: localContentHash,
                    ),
                remote =
                    PendingSyncSideMetadata(
                        locator = discoveredRemoteFile.remotePath,
                        contentHash = discoveredRemoteFile.contentMd5 ?: remoteContentHash,
                        lastModified = discoveredRemoteFile.lastModified,
                        size = discoveredRemoteFile.size ?: remoteContent?.toByteArray(Charsets.UTF_8)?.size?.toLong(),
                        etag = discoveredRemoteFile.etag ?: remoteContentHash,
                    ),
            ),
    )
}

private fun String.pendingContentHash(): String =
    toByteArray(Charsets.UTF_8).md5Hex()

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
    hasMaterializedConflict: Boolean = false,
    now: Long,
) {
    if (!protocolStateStore.incrementalSyncEnabled || !localChangeJournalStore.incrementalSyncEnabled) {
        return
    }
    if (hasMaterializedConflict) {
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
            localAuditCursor =
                when {
                    prepared.completeSnapshot -> null
                    prepared.localAuditRan -> prepared.nextLocalAuditCursor
                    else -> previousState?.localAuditCursor
                },
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
    conflictSet: SyncConflictSet? = null,
    reviewSession: SyncReviewSession? = null,
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

        reviewSession != null ->
            S3SyncResult.Review(
                message = "${reviewSession.items.size} file(s) require import review",
                review = reviewSession,
            )

        conflictSet != null ->
            S3SyncResult.Conflict(
                message = "${conflictSet.files.size} conflicting file(s) detected",
                conflicts = conflictSet,
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

internal const val S3_ACTION_CONCURRENCY = 8
internal const val S3_VERIFICATION_CONCURRENCY = 8
