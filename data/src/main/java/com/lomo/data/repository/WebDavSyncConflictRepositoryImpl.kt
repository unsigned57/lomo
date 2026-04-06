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
            val unresolvedFiles = mutableListOf<com.lomo.domain.model.SyncConflictFile>()
            val actionOutcomes =
                mutableMapOf<
                    String,
                    Pair<
                        com.lomo.domain.model.WebDavSyncDirection,
                        com.lomo.domain.model.WebDavSyncReason,
                    >,
                >()
            conflictSet.files.forEach { file ->
                val choice =
                    resolution.perFileChoices[file.relativePath]
                        ?: SyncConflictResolutionChoice.KEEP_LOCAL
                if (choice == SyncConflictResolutionChoice.SKIP_FOR_NOW) {
                    unresolvedFiles += file
                    return@forEach
                }
                applyChoice(
                    file = file,
                    choice = choice,
                    client = client,
                    layout = layout,
                )?.let { outcome ->
                    actionOutcomes[file.relativePath] = outcome
                }
            }
            return WebDavAppliedConflictResolution(
                unresolvedFiles = unresolvedFiles,
                actionOutcomes = actionOutcomes,
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
            when (choice) {
                SyncConflictResolutionChoice.KEEP_LOCAL -> {
                    val content = file.localContent ?: return null
                    client.put(
                        path = file.relativePath,
                        bytes = content.toByteArray(StandardCharsets.UTF_8),
                        contentType = fileBridge.contentTypeForPath(file.relativePath, layout),
                    )
                    return com.lomo.domain.model.WebDavSyncDirection.UPLOAD to
                        com.lomo.domain.model.WebDavSyncReason.LOCAL_NEWER
                }

                SyncConflictResolutionChoice.KEEP_REMOTE -> {
                    val content = file.remoteContent ?: return null
                    if (fileBridge.isMemoPath(file.relativePath, layout)) {
                        runtime.markdownStorageDataSource.saveFileIn(
                            directory = MemoDirectoryType.MAIN,
                            filename = fileBridge.extractMemoFilename(file.relativePath, layout),
                            content = content,
                        )
                    } else {
                        runtime.localMediaSyncStore.writeBytes(
                            file.relativePath,
                            content.toByteArray(StandardCharsets.UTF_8),
                            layout,
                        )
                    }
                    return com.lomo.domain.model.WebDavSyncDirection.DOWNLOAD to
                        com.lomo.domain.model.WebDavSyncReason.REMOTE_NEWER
                }

                SyncConflictResolutionChoice.MERGE_TEXT -> {
                    val content =
                        SyncConflictTextMerge.merge(file.localContent, file.remoteContent)
                            ?: error("Unable to merge conflict for ${file.relativePath}")
                    if (fileBridge.isMemoPath(file.relativePath, layout)) {
                        runtime.markdownStorageDataSource.saveFileIn(
                            directory = MemoDirectoryType.MAIN,
                            filename = fileBridge.extractMemoFilename(file.relativePath, layout),
                            content = content,
                        )
                    } else {
                        runtime.localMediaSyncStore.writeBytes(
                            file.relativePath,
                            content.toByteArray(StandardCharsets.UTF_8),
                            layout,
                        )
                    }
                    client.put(
                        path = file.relativePath,
                        bytes = content.toByteArray(StandardCharsets.UTF_8),
                        contentType = fileBridge.contentTypeForPath(file.relativePath, layout),
                    )
                    return com.lomo.domain.model.WebDavSyncDirection.UPLOAD to
                        com.lomo.domain.model.WebDavSyncReason.LOCAL_NEWER
                }

                SyncConflictResolutionChoice.SKIP_FOR_NOW -> return null
            }
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
