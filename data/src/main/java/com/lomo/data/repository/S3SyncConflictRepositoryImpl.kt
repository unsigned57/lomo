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
                    val remoteFiles = fileBridgeScope.remoteFiles(client, layout, config)
                    val metadataByPath = runtime.metadataDao.getAll().associateBy { it.relativePath }
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
                        )
                    }
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
        ) {
            when (choice) {
                SyncConflictResolutionChoice.KEEP_LOCAL ->
                    uploadLocalVersion(
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
                    downloadRemoteVersion(
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
        ) {
            val bytes = loadLocalBytes(file, layout, fileBridgeScope) ?: return
            val remotePath =
                remoteFiles[file.relativePath]?.remotePath
                    ?: metadataByPath[file.relativePath]?.remotePath
                    ?: encodingSupport.remotePathFor(file.relativePath, config)
            client.putObject(
                key = remotePath,
                bytes = encodingSupport.encodeContent(bytes, config),
                contentType = contentTypeForPath(file.relativePath, layout, runtime, mode),
                metadata = encodingSupport.objectMetadata(System.currentTimeMillis()),
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
        ) {
            val remotePath =
                remoteFiles[file.relativePath]?.remotePath
                    ?: metadataByPath[file.relativePath]?.remotePath
                    ?: encodingSupport.remotePathFor(file.relativePath, config)
            val payload = client.getObject(remotePath)
            val bytes = encodingSupport.decodeContent(payload.bytes, config)
            fileBridgeScope.writeLocalBytes(file.relativePath, bytes, layout)
        }

        private suspend fun refreshAfterResolution() {
            runNonFatalCatching {
                runtime.memoSynchronizer.refresh()
            }.onFailure { error ->
                Timber.w(error, "Memo refresh after S3 conflict resolution failed")
            }
        }
    }
