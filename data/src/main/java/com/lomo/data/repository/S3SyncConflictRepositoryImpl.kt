package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3RemoteVerificationLevel
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.domain.repository.S3SyncConflictRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3SyncConflictRepositoryImpl
    @Inject
    constructor(
        private val resolver: S3ConflictResolver,
    ) : S3SyncConflictRepository {
        override suspend fun resolveConflicts(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): S3SyncResult = resolver.resolveConflicts(resolution, conflictSet)
    }

@Singleton
class S3ConflictResolver
    @Inject
    constructor(
        private val runtime: S3SyncRepositoryContext,
        private val support: S3SyncRepositorySupport,
        private val encodingSupport: S3SyncEncodingSupport,
        private val fileBridge: S3SyncFileBridge,
        private val protocolStateStore: S3SyncProtocolStateStore = DisabledS3SyncProtocolStateStore,
        private val localChangeJournalStore: S3LocalChangeJournalStore = DisabledS3LocalChangeJournalStore,
        private val remoteIndexStore: S3RemoteIndexStore = DisabledS3RemoteIndexStore,
        private val pendingConflictStore: PendingSyncConflictStore = DisabledPendingSyncConflictStore,
    ) {
        suspend fun resolveConflicts(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): S3SyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val mode = resolveLocalSyncMode(runtime)
            val fileBridgeScope = fileBridge.modeAware(mode)
            return runNonFatalCatching {
                support.withClient(config) { client ->
                    val context = loadResolutionContext(conflictSet)
                    val applied =
                        applyChoices(
                            resolution = resolution,
                            conflictSet = conflictSet,
                            client = client,
                            layout = layout,
                            config = config,
                            remoteFiles = context.remoteFiles,
                            metadataByPath = context.metadataByPath,
                            fileBridgeScope = fileBridgeScope,
                            mode = mode,
                        )
                    runtime.transactionRunner.runInTransaction {
                        fileBridge.persistMetadata(
                            localFiles = applied.resolvedLocalFiles,
                            remoteFiles = applied.resolvedRemoteFiles,
                            metadataByPath = context.metadataByPath,
                            actionOutcomes = applied.actionOutcomes,
                            unresolvedPaths = applied.unresolvedPaths(),
                            completeSnapshot = false,
                        )
                        commitIncrementalConflictResolutionState(
                            layout = layout,
                            mode = mode,
                            protocolState = context.protocolState,
                            resolvedRemoteFiles = applied.resolvedRemoteFiles,
                            resolvedPaths = applied.actionOutcomes.keys,
                        )
                    }
                    refreshAfterResolution()
                    buildFinalResult(conflictSet, applied.unresolvedFiles)
                }
            }.getOrElse(support::mapError)
        }

        private suspend fun loadResolutionContext(
            conflictSet: SyncConflictSet,
        ): S3ResolutionContext {
            val protocolState = protocolStateStore.read()
            val conflictPaths = conflictSet.files.map { it.relativePath }.distinct().sorted()
            val metadataByPath =
                if (conflictPaths.isEmpty()) {
                    emptyMap()
                } else {
                    runtime.metadataDao.getByRelativePaths(conflictPaths).associateBy { it.relativePath }
                }
            return S3ResolutionContext(
                protocolState = protocolState,
                metadataByPath = metadataByPath,
                remoteFiles = loadRemoteFiles(conflictPaths, metadataByPath),
            )
        }

        private suspend fun loadRemoteFiles(
            conflictPaths: List<String>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
        ): Map<String, RemoteS3File> =
            if (remoteIndexStore.remoteIndexEnabled) {
                remoteIndexStore.readByRelativePaths(conflictPaths)
                    .asSequence()
                    .filterNot(S3RemoteIndexEntry::missingOnLastScan)
                    .associate { entry -> entry.relativePath to entry.toCachedRemoteFile() }
            } else {
                metadataByPath.values.associate { metadata ->
                    metadata.relativePath to
                        RemoteS3File(
                            path = metadata.relativePath,
                            etag = metadata.etag,
                            lastModified = metadata.remoteLastModified,
                            remotePath = metadata.remotePath,
                            verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                        )
                }
            }

        private suspend fun applyChoices(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3AppliedConflictResolution {
            val resolvedLocalFiles = mutableMapOf<String, LocalS3File>()
            val resolvedRemoteFiles = remoteFiles.toMutableMap()
            val unresolvedFiles = mutableListOf<com.lomo.domain.model.SyncConflictFile>()
            val actionOutcomes =
                mutableMapOf<
                    String,
                    Pair<
                        com.lomo.domain.model.S3SyncDirection,
                        com.lomo.domain.model.S3SyncReason,
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
                    config = config,
                    remoteFiles = remoteFiles,
                    metadataByPath = metadataByPath,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                )?.let { resolved ->
                    resolved.localFile?.let { localFile ->
                        resolvedLocalFiles[resolved.path] = localFile
                    }
                    resolvedRemoteFiles[resolved.path] = resolved.remoteFile
                    actionOutcomes[resolved.path] = resolved.direction to resolved.reason
                }
            }
            return S3AppliedConflictResolution(
                resolvedLocalFiles = resolvedLocalFiles,
                resolvedRemoteFiles = resolvedRemoteFiles,
                unresolvedFiles = unresolvedFiles,
                actionOutcomes = actionOutcomes,
            )
        }

        private suspend fun buildFinalResult(
            conflictSet: SyncConflictSet,
            unresolvedFiles: List<com.lomo.domain.model.SyncConflictFile>,
        ): S3SyncResult {
            if (unresolvedFiles.isNotEmpty()) {
                val pendingConflicts = conflictSet.copy(files = unresolvedFiles)
                pendingConflictStore.write(pendingConflicts)
                runtime.stateHolder.state.value = pendingConflicts.toS3ConflictState()
                return S3SyncResult.Conflict(
                    message = "Pending conflicts remain",
                    conflicts = pendingConflicts,
                )
            }
            pendingConflictStore.clear(conflictSet.source)
            val now = System.currentTimeMillis()
            runtime.stateHolder.state.value = S3SyncState.Success(now, "Conflicts resolved")
            return S3SyncResult.Success("Conflicts resolved")
        }

        private suspend fun applyChoice(
            file: com.lomo.domain.model.SyncConflictFile,
            choice: SyncConflictResolutionChoice,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ConflictResolutionOutcome? {
            when (choice) {
                SyncConflictResolutionChoice.KEEP_LOCAL ->
                    return uploadLocalVersion(
                        file,
                        client,
                        layout,
                        config,
                        remoteFiles,
                        metadataByPath,
                        fileBridgeScope,
                        mode,
                    )

                SyncConflictResolutionChoice.KEEP_REMOTE ->
                    return downloadRemoteVersion(
                        file,
                        client,
                        layout,
                        config,
                        remoteFiles,
                        metadataByPath,
                        fileBridgeScope,
                    )

                SyncConflictResolutionChoice.MERGE_TEXT ->
                    return mergeTextVersion(
                        file,
                        client,
                        layout,
                        config,
                        remoteFiles,
                        metadataByPath,
                        fileBridgeScope,
                        mode,
                    )

                SyncConflictResolutionChoice.SKIP_FOR_NOW -> return null
            }
        }

        private suspend fun mergeTextVersion(
            file: com.lomo.domain.model.SyncConflictFile,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ConflictResolutionOutcome {
            val mergedText =
                SyncConflictTextMerge.merge(file.localContent, file.remoteContent)
                    ?: error("Unable to merge conflict for ${file.relativePath}")
            val mergedBytes = mergedText.toByteArray(Charsets.UTF_8)
            fileBridgeScope.writeLocalBytes(file.relativePath, mergedBytes, layout)
            val localFile = fileBridgeScope.localFile(file.relativePath, layout)
            val remotePath =
                remoteFiles[file.relativePath]?.remotePath
                    ?: metadataByPath[file.relativePath]?.remotePath
                    ?: encodingSupport.remotePathFor(file.relativePath, config)
            val uploaded =
                client.putObject(
                    key = remotePath,
                    bytes = encodingSupport.encodeContent(mergedBytes, config),
                    contentType = contentTypeForPath(file.relativePath, layout, runtime, mode),
                    metadata = encodingSupport.objectMetadata(System.currentTimeMillis()),
                )
            return S3ConflictResolutionOutcome(
                path = file.relativePath,
                localFile = localFile,
                remoteFile =
                    RemoteS3File(
                        path = file.relativePath,
                        etag = uploaded.eTag,
                        lastModified = localFile?.lastModified ?: System.currentTimeMillis(),
                        remotePath = remotePath,
                    ),
                direction = com.lomo.domain.model.S3SyncDirection.UPLOAD,
                reason = com.lomo.domain.model.S3SyncReason.LOCAL_NEWER,
            )
        }

        private suspend fun uploadLocalVersion(
            file: com.lomo.domain.model.SyncConflictFile,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ConflictResolutionOutcome? {
            val bytes = loadLocalBytes(file, layout, fileBridgeScope) ?: return null
            val localFile = fileBridgeScope.localFile(file.relativePath, layout)
            val remotePath =
                remoteFiles[file.relativePath]?.remotePath
                    ?: metadataByPath[file.relativePath]?.remotePath
                    ?: encodingSupport.remotePathFor(file.relativePath, config)
            val uploaded =
                client.putObject(
                key = remotePath,
                bytes = encodingSupport.encodeContent(bytes, config),
                contentType = contentTypeForPath(file.relativePath, layout, runtime, mode),
                metadata = encodingSupport.objectMetadata(System.currentTimeMillis()),
            )
            return S3ConflictResolutionOutcome(
                path = file.relativePath,
                localFile = localFile,
                remoteFile =
                    RemoteS3File(
                        path = file.relativePath,
                        etag = uploaded.eTag,
                        lastModified = localFile?.lastModified ?: System.currentTimeMillis(),
                        remotePath = remotePath,
                    ),
                direction = com.lomo.domain.model.S3SyncDirection.UPLOAD,
                reason = com.lomo.domain.model.S3SyncReason.LOCAL_NEWER,
            )
        }

        private suspend fun downloadRemoteVersion(
            file: com.lomo.domain.model.SyncConflictFile,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
        ): S3ConflictResolutionOutcome {
            val remotePath =
                remoteFiles[file.relativePath]?.remotePath
                    ?: metadataByPath[file.relativePath]?.remotePath
                    ?: encodingSupport.remotePathFor(file.relativePath, config)
            val payload = client.getObject(remotePath)
            val bytes = encodingSupport.decodeContent(payload.bytes, config)
            fileBridgeScope.writeLocalBytes(file.relativePath, bytes, layout)
            val localFile = fileBridgeScope.localFile(file.relativePath, layout)
            return S3ConflictResolutionOutcome(
                path = file.relativePath,
                localFile = localFile,
                remoteFile =
                    RemoteS3File(
                        path = file.relativePath,
                        etag = payload.eTag ?: remoteFiles[file.relativePath]?.etag,
                        lastModified =
                            encodingSupport.resolveRemoteLastModified(payload.metadata, payload.lastModified)
                                ?: remoteFiles[file.relativePath]?.lastModified,
                        remotePath = remotePath,
                    ),
                direction = com.lomo.domain.model.S3SyncDirection.DOWNLOAD,
                reason = com.lomo.domain.model.S3SyncReason.REMOTE_NEWER,
            )
        }

        private suspend fun refreshAfterResolution() {
            runNonFatalCatching {
                runtime.memoSynchronizer.refreshImportedSync()
            }.onFailure { error ->
                Timber.w(error, "Memo refresh after S3 conflict resolution failed")
            }
        }

        private suspend fun commitIncrementalConflictResolutionState(
            layout: SyncDirectoryLayout,
            mode: S3LocalSyncMode,
            protocolState: S3SyncProtocolState?,
            resolvedRemoteFiles: Map<String, RemoteS3File>,
            resolvedPaths: Set<String>,
        ) {
            if (!protocolStateStore.incrementalSyncEnabled || !localChangeJournalStore.incrementalSyncEnabled) {
                return
            }
            val now = System.currentTimeMillis()
            if (remoteIndexStore.remoteIndexEnabled) {
                val existingByPath =
                    remoteIndexStore.readByRelativePaths(resolvedPaths).associateBy(S3RemoteIndexEntry::relativePath)
                remoteIndexStore.upsert(
                    resolvedPaths.mapNotNull { path ->
                        resolvedRemoteFiles[path]?.toRemoteIndexEntry(
                            now = now,
                            scanEpoch = protocolState?.scanEpoch ?: 0L,
                            scanPriority = existingByPath[path]?.scanPriority ?: defaultScanPriority(path),
                        )?.promoteForRecentActivity(
                            now = now,
                            scanEpoch = protocolState?.scanEpoch ?: 0L,
                        )
                    },
                )
            }
            protocolStateStore.write(
                S3SyncProtocolState(
                    protocolVersion = S3_INCREMENTAL_PROTOCOL_VERSION,
                    lastSuccessfulSyncAt = now,
                    lastFastSyncAt = now,
                    lastReconcileAt = protocolState?.lastReconcileAt,
                    lastFullRemoteScanAt = protocolState?.lastFullRemoteScanAt,
                    indexedLocalFileCount = protocolState?.indexedLocalFileCount ?: 0,
                    indexedRemoteFileCount =
                        if (remoteIndexStore.remoteIndexEnabled) {
                            remoteIndexStore.readPresentCount()
                        } else {
                            protocolState?.indexedRemoteFileCount ?: 0
                        },
                    localModeFingerprint = mode.fingerprint(),
                    remoteScanCursor = protocolState?.remoteScanCursor,
                    scanEpoch = protocolState?.scanEpoch ?: 0L,
                ),
            )
            val removableJournalIds =
                localChangeJournalStore.read().values
                    .filter { entry -> entry.relativePath(layout, mode)?.let(resolvedPaths::contains) == true }
                    .map(S3LocalChangeJournalEntry::id)
            localChangeJournalStore.remove(removableJournalIds)
        }
    }

private data class S3ResolutionContext(
    val protocolState: S3SyncProtocolState?,
    val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
    val remoteFiles: Map<String, RemoteS3File>,
)

private data class S3AppliedConflictResolution(
    val resolvedLocalFiles: Map<String, LocalS3File>,
    val resolvedRemoteFiles: MutableMap<String, RemoteS3File>,
    val unresolvedFiles: List<com.lomo.domain.model.SyncConflictFile>,
    val actionOutcomes:
        Map<
            String,
            Pair<
                com.lomo.domain.model.S3SyncDirection,
                com.lomo.domain.model.S3SyncReason,
            >,
        >,
) {
    fun unresolvedPaths(): Set<String> =
        unresolvedFiles.mapTo(linkedSetOf(), com.lomo.domain.model.SyncConflictFile::relativePath)
}

private data class S3ConflictResolutionOutcome(
    val path: String,
    val localFile: LocalS3File?,
    val remoteFile: RemoteS3File,
    val direction: com.lomo.domain.model.S3SyncDirection,
    val reason: com.lomo.domain.model.S3SyncReason,
)

private suspend fun loadLocalBytes(
    file: com.lomo.domain.model.SyncConflictFile,
    layout: SyncDirectoryLayout,
    fileBridgeScope: S3SyncFileBridgeScope,
): ByteArray? =
    fileBridgeScope.readLocalBytes(file.relativePath, layout)
        ?: file.localContent?.toByteArray(Charsets.UTF_8)
