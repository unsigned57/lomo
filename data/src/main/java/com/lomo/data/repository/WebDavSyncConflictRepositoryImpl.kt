package com.lomo.data.repository

import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.repository.WebDavSyncConflictRepository
import com.lomo.domain.repository.WebDavSyncReviewRepository
import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavSyncConflictRepositoryImpl
    @Inject
    constructor(
        private val resolver: WebDavConflictResolver,
    ) : WebDavSyncConflictRepository {
        override suspend fun resolveConflicts(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): WebDavSyncResult = resolver.resolveConflicts(resolution, conflictSet)
    }

@Singleton
class WebDavSyncReviewRepositoryImpl
    @Inject
    constructor(
        private val resolver: WebDavReviewResolver,
    ) : WebDavSyncReviewRepository {
        override suspend fun resolveReview(
            resolution: SyncReviewResolution,
            review: SyncReviewSession,
        ): WebDavSyncResult = resolver.resolveReview(resolution, review)
    }

@Singleton
class WebDavConflictResolver
    @Inject
    internal constructor(
        private val runtime: WebDavSyncRepositoryContext,
        private val support: WebDavSyncRepositorySupport,
        private val fileBridge: WebDavSyncFileBridge,
        private val pendingConflictStore: PendingSyncConflictStore,
        private val lifecycleRunner: RemoteSyncLifecycleRunner,
    ) {
        suspend fun resolveConflicts(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): WebDavSyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            return runNonFatalCatching {
                support.runWebDavIo {
                    val client = support.createClient(config)
                    lifecycleRunner.run(
                        WebDavResolutionLifecycleStages(
                            client = client,
                            workItemCount = conflictSet.files.size,
                            resolve = { meteredClient ->
                                resolveConflictsWithClient(resolution, conflictSet, meteredClient, layout)
                            },
                            mapError = support::mapError,
                        ),
                    )
                }
            }.getOrElse(support::mapError)
        }

        private suspend fun resolveConflictsWithClient(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): WebDavSyncResult {
            validatePendingConflictBeforeResolve(
                descriptor = pendingConflictStore.readDescriptor(conflictSet.source),
                client = client,
                layout = layout,
            )?.let { reason ->
                pendingConflictStore.clear(conflictSet.source)
                return WebDavSyncResult.Error("Pending WebDAV conflict session requires rebuild: $reason")
            }
            val applied = applyChoices(resolution, conflictSet, client, layout)
            val localChanged = applied.actionOutcomes.isNotEmpty()
            val remoteChanged = applied.actionOutcomes.isNotEmpty()
            fileBridge.persistMetadata(
                client = client,
                layout = layout,
                localFiles = fileBridge.localFiles(layout),
                remoteFiles = fileBridge.remoteFiles(client, layout),
                actionOutcomes = applied.actionOutcomes,
                localChanged = localChanged,
                remoteChanged = remoteChanged,
                unresolvedPaths = applied.unresolvedPaths(),
                completeSnapshot = false,
            )
            refreshAfterResolution()
            return buildFinalResult(conflictSet, applied.unresolvedFiles)
        }

        private suspend fun validatePendingConflictBeforeResolve(
            descriptor: PendingSyncConflictDescriptor?,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): PendingSyncInvalidationReason? {
            descriptor ?: return null
            descriptor.files.forEach { file ->
                val local =
                    fileBridge.localFile(file.relativePath, layout)
                        ?: return PendingSyncInvalidationReason.MISSING_LOCAL
                if (!file.local.matchesLocal(local)) return PendingSyncInvalidationReason.STALE_LOCAL
                val remote =
                    fileBridge
                        .remoteFilesInFolder(
                            client = client,
                            folderPath = file.relativePath.substringBeforeLast('/', missingDelimiterValue = ""),
                            forceRefresh = true,
                        )[file.relativePath]
                        ?: return PendingSyncInvalidationReason.MISSING_REMOTE
                if (fileBridge.isMemoPath(file.relativePath, layout)) {
                    // Memo/text staleness is validated by content equality; etag/lastModified can drift on
                    // an unchanged note and would otherwise force a redundant rebuild + second apply.
                    val localContent =
                        runtime.markdownStorageDataSource.readFileIn(
                            MemoDirectoryType.MAIN,
                            fileBridge.extractMemoFilename(file.relativePath, layout),
                        )
                    val remoteContent = String(client.getSmallFile(file.relativePath).bytes, StandardCharsets.UTF_8)
                    if (!file.local.matchesContent(localContent)) return PendingSyncInvalidationReason.STALE_LOCAL
                    if (!file.remote.matchesContent(remoteContent)) return PendingSyncInvalidationReason.STALE_REMOTE
                } else if (!file.remote.matchesRemote(remote.etag, remote.lastModified, remote.size)) {
                    return PendingSyncInvalidationReason.STALE_REMOTE
                }
            }
            return null
        }

        private suspend fun applyChoices(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): WebDavAppliedConflictResolution {
            val batch =
                applyFileConflictChoices(
                    conflictSet = conflictSet,
                    resolution = resolution,
                    defaultChoice = SyncConflictResolutionChoice.KEEP_LOCAL,
                ) { file, choice ->
                    applyChoice(
                        file = file,
                        choice = choice,
                        client = client,
                        layout = layout,
                    )?.let { FileConflictApplication.Applied(it) }
                        ?: FileConflictApplication.Unresolved as FileConflictApplication<
                            Pair<
                                com.lomo.domain.model.WebDavSyncDirection,
                                com.lomo.domain.model.WebDavSyncReason,
                            >
                        >
                }
            return WebDavAppliedConflictResolution(
                unresolvedFiles = batch.unresolvedFiles,
                actionOutcomes = batch.appliedChoices.associate { applied -> applied.path to applied.value },
            )
        }

        private suspend fun buildFinalResult(
            conflictSet: SyncConflictSet,
            unresolvedFiles: List<com.lomo.domain.model.SyncConflictFile>,
        ): WebDavSyncResult {
            if (unresolvedFiles.isNotEmpty()) {
                val pendingConflicts = conflictSet.copy(files = unresolvedFiles)
                pendingConflictStore.write(pendingConflicts)
                runtime.stateHolder.state.value = WebDavSyncState.ConflictDetected(pendingConflicts)
                return WebDavSyncResult.Conflict("Pending conflicts remain", pendingConflicts)
            }
            pendingConflictStore.clear(conflictSet.source)
            val now = System.currentTimeMillis()
            runtime.stateHolder.state.value = WebDavSyncState.Success(now, "Conflicts resolved")
            return WebDavSyncResult.Success("Conflicts resolved")
        }

        private suspend fun applyChoice(
            file: com.lomo.domain.model.SyncConflictFile,
            choice: SyncConflictResolutionChoice,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): Pair<
            com.lomo.domain.model.WebDavSyncDirection,
            com.lomo.domain.model.WebDavSyncReason,
        >? {
            val isMemoPath =
                isWebDavMemoPath(file.relativePath, layout) ||
                    file.relativePath.endsWith(WEBDAV_MEMO_SUFFIX)
            return when (choice) {
                SyncConflictResolutionChoice.KEEP_LOCAL ->
                    keepLocalChoice(file, client, layout, isMemoPath)

                SyncConflictResolutionChoice.KEEP_REMOTE ->
                    keepRemoteChoice(file, client, layout, isMemoPath)

                SyncConflictResolutionChoice.MERGE_TEXT ->
                    mergeTextChoice(file, client, layout, isMemoPath)

                SyncConflictResolutionChoice.SKIP_FOR_NOW -> null
            }
        }

        private suspend fun keepLocalChoice(
            file: com.lomo.domain.model.SyncConflictFile,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            isMemoPath: Boolean,
        ): Pair<
            com.lomo.domain.model.WebDavSyncDirection,
            com.lomo.domain.model.WebDavSyncReason,
        >? {
            val contentType = webDavContentTypeForPath(file.relativePath, layout, runtime)
            if (!isMemoPath) {
                file.localContent?.let { content ->
                    withTempFile(file.relativePath.transferSuffix()) { transferFile ->
                        transferFile.writeText(content, StandardCharsets.UTF_8)
                        client.putFile(
                            path = file.relativePath,
                            file = transferFile,
                            contentType = contentType,
                        )
                    }
                } ?: withTempFile(file.relativePath.transferSuffix()) { transferFile ->
                    runtime.localMediaSyncStore.exportToFile(file.relativePath, layout, transferFile)
                    client.putFile(
                        path = file.relativePath,
                        file = transferFile,
                        contentType = contentType,
                    )
                }
            } else {
                val content = file.localContent ?: return null
                client.putSmallFile(
                    path = file.relativePath,
                    bytes = content.toByteArray(StandardCharsets.UTF_8),
                    contentType = contentType,
                )
            }
            return com.lomo.domain.model.WebDavSyncDirection.UPLOAD to
                com.lomo.domain.model.WebDavSyncReason.LOCAL_NEWER
        }

        private suspend fun keepRemoteChoice(
            file: com.lomo.domain.model.SyncConflictFile,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            isMemoPath: Boolean,
        ): Pair<
            com.lomo.domain.model.WebDavSyncDirection,
            com.lomo.domain.model.WebDavSyncReason,
        >? {
            if (!isMemoPath) {
                file.remoteContent?.let { remoteContent ->
                    runtime.localMediaSyncStore.writeBytes(
                        file.relativePath,
                        remoteContent.toByteArray(StandardCharsets.UTF_8),
                        layout,
                    )
                } ?: withTempFile(file.relativePath.transferSuffix()) { transferFile ->
                    client.getToFile(file.relativePath, transferFile)
                    runtime.localMediaSyncStore.importFromFile(file.relativePath, transferFile, layout)
                }
            } else {
                val content = file.remoteContent ?: return null
                runtime.markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = extractWebDavMemoFilename(file.relativePath, layout),
                    content = content,
                )
            }
            return com.lomo.domain.model.WebDavSyncDirection.DOWNLOAD to
                com.lomo.domain.model.WebDavSyncReason.REMOTE_NEWER
        }

        private suspend fun mergeTextChoice(
            file: com.lomo.domain.model.SyncConflictFile,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            isMemoPath: Boolean,
        ): Pair<
            com.lomo.domain.model.WebDavSyncDirection,
            com.lomo.domain.model.WebDavSyncReason,
        >? {
            if (!isMemoPath) {
                return null
            }
            val content =
                SyncConflictTextMerge.merge(
                    localText = file.localContent,
                    remoteText = file.remoteContent,
                    localLastModified = file.localLastModified,
                    remoteLastModified = file.remoteLastModified,
                ) ?: return null
            runtime.markdownStorageDataSource.saveFileIn(
                directory = MemoDirectoryType.MAIN,
                filename = extractWebDavMemoFilename(file.relativePath, layout),
                content = content,
            )
            client.putSmallFile(
                path = file.relativePath,
                bytes = content.toByteArray(StandardCharsets.UTF_8),
                contentType = webDavContentTypeForPath(file.relativePath, layout, runtime),
            )
            return com.lomo.domain.model.WebDavSyncDirection.UPLOAD to
                com.lomo.domain.model.WebDavSyncReason.LOCAL_NEWER
        }

        private suspend fun refreshAfterResolution() {
            runNonFatalCatching {
                runtime.memoSynchronizer.refresh()
            }.onFailure { error ->
                Timber.w(error, "Memo refresh after WebDAV conflict resolution failed")
            }
        }
    }

@Singleton
class WebDavReviewResolver
    @Inject
    internal constructor(
        private val runtime: WebDavSyncRepositoryContext,
        private val support: WebDavSyncRepositorySupport,
        private val fileBridge: WebDavSyncFileBridge,
        private val pendingReviewStore: PendingSyncReviewStore,
        private val lifecycleRunner: RemoteSyncLifecycleRunner,
    ) {
        suspend fun resolveReview(
            resolution: SyncReviewResolution,
            review: SyncReviewSession,
        ): WebDavSyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            return runNonFatalCatching {
                support.runWebDavIo {
                    val client = support.createClient(config)
                    lifecycleRunner.run(
                        WebDavResolutionLifecycleStages(
                            client = client,
                            workItemCount = review.items.size,
                            resolve = { meteredClient ->
                                resolveReviewWithClient(resolution, review, meteredClient, layout)
                            },
                            mapError = support::mapError,
                        ),
                    )
                }
            }.getOrElse(support::mapError)
        }

        private suspend fun resolveReviewWithClient(
            resolution: SyncReviewResolution,
            review: SyncReviewSession,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): WebDavSyncResult {
            val validatedReview =
                when (
                    val restored =
                        pendingReviewStore.readDescriptor(review.source)?.let { descriptor ->
                            WebDavPendingReviewRestorer(
                                runtime = runtime,
                                client = client,
                                layout = layout,
                                fileBridge = fileBridge,
                            ).restore(descriptor)
                        }
                ) {
                    null -> review
                    is PendingSyncRestoreResult.Restored -> restored.session
                    is PendingSyncRestoreResult.Invalidated -> {
                        pendingReviewStore.clear(review.source)
                        return WebDavSyncResult.Error(
                            "Pending WebDAV review session requires rebuild: ${restored.reason}",
                        )
                    }
                    is PendingSyncRestoreResult.Failed ->
                        return WebDavSyncResult.Error(
                            message = "Pending WebDAV review session restore failed: ${restored.error.category}",
                            exception = restored.error.cause,
                        )
                }
            val applied = applyChoices(resolution, validatedReview, client, layout)
            if (applied.actionOutcomes.isNotEmpty()) {
                fileBridge.persistMetadata(
                    client = client,
                    layout = layout,
                    localFiles = fileBridge.localFiles(layout),
                    remoteFiles = fileBridge.remoteFiles(client, layout),
                    actionOutcomes = applied.actionOutcomes,
                    localChanged = true,
                    remoteChanged = true,
                    unresolvedPaths = applied.unresolvedPaths(),
                    completeSnapshot = false,
                )
                refreshAfterResolution()
            }
            return buildFinalResult(validatedReview, applied.unresolvedItems)
        }

        private suspend fun applyChoices(
            resolution: SyncReviewResolution,
            review: SyncReviewSession,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): WebDavAppliedReviewResolution {
            val unresolvedItems = mutableListOf<SyncReviewItem>()
            val actionOutcomes =
                review.items.mapNotNull { item ->
                    val choice =
                        resolution.perItemChoices[item.relativePath]
                            ?: SyncReviewResolutionChoice.SKIP_FOR_NOW
                    if (
                        item.state == SyncReviewItemState.BLOCKED ||
                        choice == SyncReviewResolutionChoice.SKIP_FOR_NOW
                    ) {
                        unresolvedItems += item
                        return@mapNotNull null
                    }
                    applyChoice(item, choice, client, layout)?.let { item.relativePath to it }
                        ?: run {
                            unresolvedItems += item
                            null
                        }
                }.toMap()
            return WebDavAppliedReviewResolution(
                unresolvedItems = unresolvedItems,
                actionOutcomes = actionOutcomes,
            )
        }

        private suspend fun buildFinalResult(
            review: SyncReviewSession,
            unresolvedItems: List<SyncReviewItem>,
        ): WebDavSyncResult {
            if (unresolvedItems.isNotEmpty()) {
                val pendingReview = review.copy(items = unresolvedItems)
                pendingReviewStore.write(pendingReview)
                runtime.stateHolder.state.value = WebDavSyncState.ReviewingInitialSync(pendingReview)
                return WebDavSyncResult.Review("Pending review items remain", pendingReview)
            }
            pendingReviewStore.clear(review.source)
            val now = System.currentTimeMillis()
            runtime.stateHolder.state.value = WebDavSyncState.Success(now, "Review resolved")
            return WebDavSyncResult.Success("Review resolved")
        }

        private suspend fun applyChoice(
            item: SyncReviewItem,
            choice: SyncReviewResolutionChoice,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ): Pair<
            com.lomo.domain.model.WebDavSyncDirection,
            com.lomo.domain.model.WebDavSyncReason,
        >? {
            val isMemoPath =
                isWebDavMemoPath(item.relativePath, layout) ||
                    item.relativePath.endsWith(WEBDAV_MEMO_SUFFIX)
            return when (choice) {
                SyncReviewResolutionChoice.KEEP_LOCAL ->
                    keepLocalChoice(item, client, layout, isMemoPath)

                SyncReviewResolutionChoice.KEEP_INCOMING ->
                    keepIncomingChoice(item, client, layout, isMemoPath)

                SyncReviewResolutionChoice.MERGE_TEXT ->
                    mergeTextChoice(item, client, layout, isMemoPath)

                SyncReviewResolutionChoice.SKIP_FOR_NOW -> null
            }
        }

        private suspend fun keepLocalChoice(
            item: SyncReviewItem,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            isMemoPath: Boolean,
        ): Pair<
            com.lomo.domain.model.WebDavSyncDirection,
            com.lomo.domain.model.WebDavSyncReason,
        >? {
            val contentType = webDavContentTypeForPath(item.relativePath, layout, runtime)
            if (!isMemoPath) {
                item.localContent?.let { content ->
                    withTempFile(item.relativePath.transferSuffix()) { transferFile ->
                        transferFile.writeText(content, StandardCharsets.UTF_8)
                        client.putFile(
                            path = item.relativePath,
                            file = transferFile,
                            contentType = contentType,
                        )
                    }
                } ?: withTempFile(item.relativePath.transferSuffix()) { transferFile ->
                    runtime.localMediaSyncStore.exportToFile(item.relativePath, layout, transferFile)
                    client.putFile(
                        path = item.relativePath,
                        file = transferFile,
                        contentType = contentType,
                    )
                }
            } else {
                val content = item.localContent ?: return null
                client.putSmallFile(
                    path = item.relativePath,
                    bytes = content.toByteArray(StandardCharsets.UTF_8),
                    contentType = contentType,
                )
            }
            return com.lomo.domain.model.WebDavSyncDirection.UPLOAD to
                com.lomo.domain.model.WebDavSyncReason.LOCAL_NEWER
        }

        private suspend fun keepIncomingChoice(
            item: SyncReviewItem,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            isMemoPath: Boolean,
        ): Pair<
            com.lomo.domain.model.WebDavSyncDirection,
            com.lomo.domain.model.WebDavSyncReason,
        >? {
            if (!isMemoPath) {
                item.incomingContent?.let { incomingContent ->
                    runtime.localMediaSyncStore.writeBytes(
                        item.relativePath,
                        incomingContent.toByteArray(StandardCharsets.UTF_8),
                        layout,
                    )
                } ?: withTempFile(item.relativePath.transferSuffix()) { transferFile ->
                    client.getToFile(item.relativePath, transferFile)
                    runtime.localMediaSyncStore.importFromFile(item.relativePath, transferFile, layout)
                }
            } else {
                val content = item.incomingContent ?: return null
                runtime.markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = extractWebDavMemoFilename(item.relativePath, layout),
                    content = content,
                )
            }
            return com.lomo.domain.model.WebDavSyncDirection.DOWNLOAD to
                com.lomo.domain.model.WebDavSyncReason.REMOTE_NEWER
        }

        private suspend fun mergeTextChoice(
            item: SyncReviewItem,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
            isMemoPath: Boolean,
        ): Pair<
            com.lomo.domain.model.WebDavSyncDirection,
            com.lomo.domain.model.WebDavSyncReason,
        >? {
            if (!isMemoPath) {
                return null
            }
            val content =
                SyncConflictTextMerge.merge(
                    localText = item.localContent,
                    remoteText = item.incomingContent,
                    localLastModified = item.localLastModified,
                    remoteLastModified = item.incomingLastModified,
                ) ?: return null
            runtime.markdownStorageDataSource.saveFileIn(
                directory = MemoDirectoryType.MAIN,
                filename = extractWebDavMemoFilename(item.relativePath, layout),
                content = content,
            )
            client.putSmallFile(
                path = item.relativePath,
                bytes = content.toByteArray(StandardCharsets.UTF_8),
                contentType = webDavContentTypeForPath(item.relativePath, layout, runtime),
            )
            return com.lomo.domain.model.WebDavSyncDirection.UPLOAD to
                com.lomo.domain.model.WebDavSyncReason.LOCAL_NEWER
        }

        private suspend fun refreshAfterResolution() {
            runNonFatalCatching {
                runtime.memoSynchronizer.refresh()
            }.onFailure { error ->
                Timber.w(error, "Memo refresh after WebDAV review resolution failed")
            }
        }
    }

private suspend fun <T> withTempFile(
    suffix: String,
    block: suspend (File) -> T,
): T {
    val file = createTempFile(prefix = "webdav-resolution-", suffix = suffix).toFile()
    return try {
        block(file)
    } finally {
        file.delete()
    }
}

private fun String.transferSuffix(): String =
    substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" } ?: ".tmp"

private class WebDavPendingReviewRestorer(
    private val runtime: WebDavSyncRepositoryContext,
    private val client: WebDavClient,
    private val layout: SyncDirectoryLayout,
    private val fileBridge: WebDavSyncFileBridge,
) : PendingSyncReviewRestorer {
    override suspend fun restore(
        descriptor: PendingSyncReviewDescriptor,
    ): PendingSyncRestoreResult<SyncReviewSession> {
        val restoredItems = mutableListOf<SyncReviewItem>()
        var invalidation: PendingSyncInvalidationReason? = null
        val iterator = descriptor.items.iterator()
        while (invalidation == null && iterator.hasNext()) {
            when (val restored = restoreItem(iterator.next())) {
                is WebDavPendingReviewItemRestore.Invalidated -> invalidation = restored.reason
                is WebDavPendingReviewItemRestore.Restored -> restoredItems += restored.item
            }
        }
        return invalidation?.let { reason -> PendingSyncRestoreResult.Invalidated(reason) }
            ?: PendingSyncRestoreResult.Restored(
                SyncReviewSession(
                    source = descriptor.source,
                    items = restoredItems,
                    timestamp = descriptor.timestamp,
                    kind = descriptor.kind,
                ),
            )
    }

    private suspend fun restoreItem(item: PendingSyncReviewItemDescriptor): WebDavPendingReviewItemRestore {
        val local =
            fileBridge.localFile(item.relativePath, layout)
                ?: return WebDavPendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.MISSING_LOCAL)
        if (!item.local.matchesLocal(local)) {
            return WebDavPendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
        }
        val remote =
            fileBridge
                .remoteFilesInFolder(
                    client = client,
                    folderPath = item.relativePath.substringBeforeLast('/', missingDelimiterValue = ""),
                    forceRefresh = true,
                )[item.relativePath]
                ?: return WebDavPendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.MISSING_REMOTE)
        // Memo/text incoming staleness is validated by content equality in restoreContents; only binary
        // items rely on the etag/lastModified/size comparison that can drift on an unchanged memo.
        if (!fileBridge.isMemoPath(item.relativePath, layout) &&
            !item.incoming.matchesRemote(remote.etag, remote.lastModified, remote.size)
        ) {
            return WebDavPendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
        }
        return restoreContents(item, local, remote.lastModified)
    }

    private suspend fun restoreContents(
        item: PendingSyncReviewItemDescriptor,
        local: LocalWebDavFile,
        remoteLastModified: Long?,
    ): WebDavPendingReviewItemRestore {
        val isMemoPath = fileBridge.isMemoPath(item.relativePath, layout)
        val localContent =
            if (isMemoPath) {
                runtime.markdownStorageDataSource.readFileIn(
                    MemoDirectoryType.MAIN,
                    fileBridge.extractMemoFilename(item.relativePath, layout),
                )
            } else {
                null
            }
        val incomingContent =
            if (isMemoPath) {
                String(client.getSmallFile(item.relativePath).bytes, StandardCharsets.UTF_8)
            } else {
                null
            }
        return when {
            isMemoPath && !item.local.matchesContent(localContent) ->
                WebDavPendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
            isMemoPath && !item.incoming.matchesContent(incomingContent) ->
                WebDavPendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            else ->
                WebDavPendingReviewItemRestore.Restored(
                    SyncReviewItem(
                        relativePath = item.relativePath,
                        localContent = localContent,
                        incomingContent = incomingContent,
                        isBinary = item.isBinary,
                        localLastModified = local.lastModified,
                        incomingLastModified = remoteLastModified,
                        state = item.state,
                        message = item.message,
                    ),
                )
        }
    }
}

private sealed interface WebDavPendingReviewItemRestore {
    data class Restored(
        val item: SyncReviewItem,
    ) : WebDavPendingReviewItemRestore

    data class Invalidated(
        val reason: PendingSyncInvalidationReason,
    ) : WebDavPendingReviewItemRestore
}

private class WebDavResolutionLifecycleStages(
    private val client: WebDavClient,
    private val workItemCount: Int,
    private val resolve: suspend (WebDavClient) -> WebDavSyncResult,
    private val mapError: (Throwable) -> WebDavSyncResult,
) : RemoteSyncLifecycleStages<
        Unit,
        Int,
        Int,
        Unit,
        WebDavSyncResult,
        WebDavSyncResult,
        WebDavSyncResult,
        WebDavSyncResult,
    > {
    override val context: RemoteSyncLifecycleContext =
        RemoteSyncLifecycleContext(
            backend = com.lomo.domain.model.SyncBackendType.WEBDAV,
            budget = RemoteSyncBudgetPolicy.Limited(DEFAULT_REMOTE_SYNC_NETWORK_OPERATION_BUDGET),
        )

    private lateinit var meteredClient: WebDavClient

    override suspend fun loadSnapshot(session: RemoteSyncLifecycleSession) {
        meteredClient = session.meter(client)
    }

    override suspend fun plan(
        snapshot: Unit,
        session: RemoteSyncLifecycleSession,
    ): Int = workItemCount

    override suspend fun verify(
        plan: Int,
        session: RemoteSyncLifecycleSession,
    ): Int = plan

    override suspend fun materializeConflicts(
        verified: Int,
        session: RemoteSyncLifecycleSession,
    ) = Unit

    override suspend fun apply(
        verified: Int,
        conflicts: Unit,
        session: RemoteSyncLifecycleSession,
    ): WebDavSyncResult = resolve(meteredClient)

    override suspend fun commitMetadata(
        verified: Int,
        conflicts: Unit,
        applied: WebDavSyncResult,
        session: RemoteSyncLifecycleSession,
    ): WebDavSyncResult = applied

    override suspend fun finalize(
        verified: Int,
        conflicts: Unit,
        applied: WebDavSyncResult,
        metadata: WebDavSyncResult,
        session: RemoteSyncLifecycleSession,
    ): WebDavSyncResult = metadata

    override fun summarizeSnapshot(snapshot: Unit): RemoteSyncSnapshotTelemetry =
        RemoteSyncSnapshotTelemetry(
            localFileCount = workItemCount,
            remoteFileCount = workItemCount,
            metadataEntryCount = workItemCount,
        )

    override fun summarizePlan(plan: Int): RemoteSyncActionTelemetry =
        RemoteSyncActionTelemetry(total = plan, conflict = plan)

    override fun summarizeVerification(verified: Int): RemoteSyncActionTelemetry =
        RemoteSyncActionTelemetry(total = verified, conflict = verified)

    override fun summarizeRefresh(finalized: WebDavSyncResult): RemoteSyncRefreshTelemetry =
        RemoteSyncRefreshTelemetry(durationMillis = 0)

    override fun summarizeResult(finalized: WebDavSyncResult): RemoteSyncLifecycleResultTelemetry =
        if (finalized is WebDavSyncResult.Error) {
            RemoteSyncLifecycleResultTelemetry.Failure
        } else {
            RemoteSyncLifecycleResultTelemetry.Success
        }

    override fun mapResult(finalized: WebDavSyncResult): WebDavSyncResult = finalized

    override fun mapError(error: Throwable): WebDavSyncResult = mapError.invoke(error)

    override suspend fun release() = Unit
}

private data class WebDavAppliedConflictResolution(
    val unresolvedFiles: List<com.lomo.domain.model.SyncConflictFile>,
    val actionOutcomes:
        Map<
            String,
            Pair<
                com.lomo.domain.model.WebDavSyncDirection,
                com.lomo.domain.model.WebDavSyncReason,
            >,
        >,
) {
    fun unresolvedPaths(): Set<String> =
        unresolvedFiles.mapTo(linkedSetOf(), com.lomo.domain.model.SyncConflictFile::relativePath)
}

private data class WebDavAppliedReviewResolution(
    val unresolvedItems: List<SyncReviewItem>,
    val actionOutcomes:
        Map<
            String,
            Pair<
                com.lomo.domain.model.WebDavSyncDirection,
                com.lomo.domain.model.WebDavSyncReason,
            >,
        >,
) {
    fun unresolvedPaths(): Set<String> =
        unresolvedItems.mapTo(linkedSetOf(), SyncReviewItem::relativePath)
}
