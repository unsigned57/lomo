package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
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
                    val protocolState = protocolStateStore.read()
                    val conflictPaths = conflictSet.files.map { it.relativePath }.distinct().sorted()
                    val metadataByPath =
                        if (conflictPaths.isEmpty()) {
                            emptyMap()
                        } else {
                            runtime.metadataDao.getByRelativePaths(conflictPaths).associateBy { it.relativePath }
                        }
                    val remoteFiles =
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
                    val resolvedLocalFiles = mutableMapOf<String, LocalS3File>()
                    val resolvedRemoteFiles = remoteFiles.toMutableMap()
                    val actionOutcomes =
                        mutableMapOf<
                            String,
                            Pair<
                                com.lomo.domain.model.S3SyncDirection,
                                com.lomo.domain.model.S3SyncReason,
                            >,
                        >()
                    conflictSet.files.forEach { file ->
                        applyChoice(
                            file = file,
                            choice =
                                resolution.perFileChoices[file.relativePath]
                                    ?: SyncConflictResolutionChoice.KEEP_LOCAL,
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
                    fileBridge.persistMetadata(
                        localFiles = resolvedLocalFiles,
                        remoteFiles = resolvedRemoteFiles,
                        metadataByPath = metadataByPath,
                        actionOutcomes = actionOutcomes,
                        unresolvedPaths = emptySet(),
                        completeSnapshot = false,
                    )
                    commitIncrementalConflictResolutionState(
                        layout = layout,
                        mode = mode,
                        protocolState = protocolState,
                        resolvedRemoteFiles = resolvedRemoteFiles,
                        resolvedPaths = actionOutcomes.keys,
                    )
                    refreshAfterResolution()
                    val now = System.currentTimeMillis()
                    runtime.stateHolder.state.value = S3SyncState.Success(now, "Conflicts resolved")
                    S3SyncResult.Success("Conflicts resolved")
                }
            }.getOrElse(support::mapError)
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
            }
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

        private suspend fun loadLocalBytes(
            file: com.lomo.domain.model.SyncConflictFile,
            layout: SyncDirectoryLayout,
            fileBridgeScope: S3SyncFileBridgeScope,
        ): ByteArray? =
            fileBridgeScope.readLocalBytes(file.relativePath, layout)
                ?: file.localContent?.toByteArray(Charsets.UTF_8)

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
                remoteIndexStore.upsert(
                    resolvedPaths.mapNotNull { path ->
                        resolvedRemoteFiles[path]?.toRemoteIndexEntry(
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

private data class S3ConflictResolutionOutcome(
    val path: String,
    val localFile: LocalS3File?,
    val remoteFile: RemoteS3File,
    val direction: com.lomo.domain.model.S3SyncDirection,
    val reason: com.lomo.domain.model.S3SyncReason,
)
