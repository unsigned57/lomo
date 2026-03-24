package com.lomo.data.repository

import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
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
                    conflictSet.files.forEach { file ->
                        applyChoice(
                            file = file,
                            choice =
                                resolution.perFileChoices[file.relativePath]
                                    ?: SyncConflictResolutionChoice.KEEP_LOCAL,
                            client = client,
                            layout = layout,
                        )
                    }
                    refreshAfterResolution()
                    val now = System.currentTimeMillis()
                    runtime.stateHolder.state.value = WebDavSyncState.Success(now, "Conflicts resolved")
                    WebDavSyncResult.Success("Conflicts resolved")
                }
            }.getOrElse(support::mapError)
        }

        private suspend fun applyChoice(
            file: com.lomo.domain.model.SyncConflictFile,
            choice: SyncConflictResolutionChoice,
            client: WebDavClient,
            layout: SyncDirectoryLayout,
        ) {
            when (choice) {
                SyncConflictResolutionChoice.KEEP_LOCAL -> {
                    val content = file.localContent ?: return
                    client.put(
                        path = file.relativePath,
                        bytes = content.toByteArray(StandardCharsets.UTF_8),
                        contentType = fileBridge.contentTypeForPath(file.relativePath, layout),
                    )
                }

                SyncConflictResolutionChoice.KEEP_REMOTE -> {
                    val content = file.remoteContent ?: return
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
                }
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
