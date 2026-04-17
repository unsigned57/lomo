package com.lomo.data.repository

import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.domain.repository.WebDavSyncConflictRepository
import timber.log.Timber
import java.nio.charset.StandardCharsets
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
class WebDavConflictResolver
    @Inject
    constructor(
        private val runtime: WebDavSyncRepositoryContext,
        private val support: WebDavSyncRepositorySupport,
        private val fileBridge: WebDavSyncFileBridge,
        private val pendingConflictStore: PendingSyncConflictStore = DisabledPendingSyncConflictStore,
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
                    buildFinalResult(conflictSet, applied.unresolvedFiles)
                }
            }.getOrElse(support::mapError)
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
                fileBridge.isMemoPath(file.relativePath, layout) ||
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
            val localBytes =
                if (!isMemoPath) {
                    file.localContent?.toByteArray(StandardCharsets.UTF_8)
                        ?: runtime.localMediaSyncStore.readBytes(file.relativePath, layout)
                } else {
                    val content = file.localContent ?: return null
                    content.toByteArray(StandardCharsets.UTF_8)
                }
            client.put(
                path = file.relativePath,
                bytes = localBytes,
                contentType = fileBridge.contentTypeForPath(file.relativePath, layout),
            )
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
                val remoteBytes =
                    file.remoteContent?.toByteArray(StandardCharsets.UTF_8)
                        ?: client.get(file.relativePath).bytes
                runtime.localMediaSyncStore.writeBytes(
                    file.relativePath,
                    remoteBytes,
                    layout,
                )
            } else {
                val content = file.remoteContent ?: return null
                runtime.markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = fileBridge.extractMemoFilename(file.relativePath, layout),
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
                )
                    ?: error("Unable to merge conflict for ${file.relativePath}")
            runtime.markdownStorageDataSource.saveFileIn(
                directory = MemoDirectoryType.MAIN,
                filename = fileBridge.extractMemoFilename(file.relativePath, layout),
                content = content,
            )
            client.put(
                path = file.relativePath,
                bytes = content.toByteArray(StandardCharsets.UTF_8),
                contentType = fileBridge.contentTypeForPath(file.relativePath, layout),
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
