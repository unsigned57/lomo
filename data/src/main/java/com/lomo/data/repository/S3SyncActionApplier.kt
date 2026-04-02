package com.lomo.data.repository

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncState
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class S3SyncActionApplier
    @Inject
    constructor(
        private val runtime: S3SyncRepositoryContext,
        private val encodingSupport: S3SyncEncodingSupport,
        fileBridge: S3SyncFileBridge,
    ) {
        internal suspend fun applyAction(
            action: S3SyncAction,
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            localFiles: Map<String, LocalS3File>,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ActionExecutionState =
            runNonFatalCatching {
                when (action.direction) {
                    S3SyncDirection.UPLOAD ->
                        uploadAction(
                            action = action,
                            client = client,
                            layout = layout,
                            config = config,
                            localFiles = localFiles,
                            remoteFiles = remoteFiles,
                            metadataByPath = metadataByPath,
                            fileBridgeScope = fileBridgeScope,
                            mode = mode,
                        )

                    S3SyncDirection.DOWNLOAD ->
                        downloadAction(
                            action,
                            client,
                            layout,
                            config,
                            remoteFiles,
                            metadataByPath,
                            fileBridgeScope,
                            mode,
                        )

                    S3SyncDirection.DELETE_LOCAL -> deleteLocalAction(action, layout, fileBridgeScope, mode)
                    S3SyncDirection.DELETE_REMOTE ->
                        deleteRemoteAction(action, client, config, remoteFiles, metadataByPath)
                    S3SyncDirection.NONE,
                    S3SyncDirection.CONFLICT,
                    -> S3ActionExecutionState.Skipped
                }
            }.getOrElse { error ->
                Timber.e(error, "Failed to %s %s", action.operationName(), action.path)
                S3ActionExecutionState.Failed(action.path)
            }

        private suspend fun uploadAction(
            action: S3SyncAction,
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            localFiles: Map<String, LocalS3File>,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ActionExecutionState {
            runtime.stateHolder.state.value = S3SyncState.Uploading
            val bytes = loadUploadBytes(action, layout, fileBridgeScope) ?: return S3ActionExecutionState.Skipped
            val remotePath =
                encodingSupport.remotePathFor(
                    relativePath = action.path,
                    config = config,
                    existingRemotePath =
                        remoteFiles[action.path]?.remotePath ?: metadataByPath[action.path]?.remotePath,
                )
            val uploaded = client.putObject(
                key = remotePath,
                bytes = encodingSupport.encodeContent(bytes, config),
                contentType = contentTypeForPath(action.path, layout, runtime, mode),
                metadata = encodingSupport.objectMetadata(localFiles[action.path]?.lastModified),
            )
            return S3ActionExecutionState.Applied(
                localChanged = false,
                remoteChanged = true,
                updatedRemoteFile =
                    RemoteS3File(
                        path = action.path,
                        etag = uploaded.eTag,
                        lastModified = localFiles[action.path]?.lastModified,
                        remotePath = remotePath,
                    ),
            )
        }

        private suspend fun loadUploadBytes(
            action: S3SyncAction,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            fileBridgeScope: S3SyncFileBridgeScope,
        ): ByteArray? {
            val bytes = fileBridgeScope.readLocalBytes(action.path, layout)
            if (bytes == null) {
                Timber.w("Local file missing during upload: %s, skipping", action.path)
            }
            return bytes
        }

        private suspend fun downloadAction(
            action: S3SyncAction,
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ActionExecutionState {
            runtime.stateHolder.state.value = S3SyncState.Downloading
            val remotePath = resolveRemotePath(action.path, remoteFiles, metadataByPath, config)
            val remoteFile = client.getObject(remotePath)
            val bytes = encodingSupport.decodeContent(remoteFile.bytes, config)
            fileBridgeScope.writeLocalBytes(action.path, bytes, layout)
            val updatedLocalFile = fileBridgeScope.localFile(action.path, layout)
            return S3ActionExecutionState.Applied(
                localChanged = true,
                remoteChanged = false,
                updatedLocalFile = updatedLocalFile,
                memoRefreshPlan = buildMemoRefreshPlan(action.path, layout, mode),
            )
        }

        private suspend fun deleteLocalAction(
            action: S3SyncAction,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ActionExecutionState {
            runtime.stateHolder.state.value = S3SyncState.Deleting
            fileBridgeScope.deleteLocalFile(action.path, layout)
            return S3ActionExecutionState.Applied(
                localChanged = true,
                remoteChanged = false,
                deletedLocalPath = action.path,
                memoRefreshPlan = buildDeleteMemoRefreshPlan(action.path, layout, mode),
            )
        }

        private suspend fun deleteRemoteAction(
            action: S3SyncAction,
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
        ): S3ActionExecutionState {
            runtime.stateHolder.state.value = S3SyncState.Deleting
            client.deleteObject(resolveRemotePath(action.path, remoteFiles, metadataByPath, config))
            return S3ActionExecutionState.Applied(
                localChanged = false,
                remoteChanged = true,
                deletedRemotePath = action.path,
            )
        }

        private fun resolveRemotePath(
            relativePath: String,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            config: S3ResolvedConfig,
        ): String =
            remoteFiles[relativePath]?.remotePath
                ?: metadataByPath[relativePath]?.remotePath
                ?: encodingSupport.remotePathFor(relativePath, config)

        private suspend fun buildMemoRefreshPlan(
            path: String,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            mode: S3LocalSyncMode,
        ): S3MemoRefreshPlan {
            if (!isMemoPath(path, layout, mode)) {
                return S3MemoRefreshPlan.None
            }
            val target = resolveMemoRefreshTarget(path, layout, mode)
            return target?.let(S3MemoRefreshPlan::Targets) ?: S3MemoRefreshPlan.Full
        }

        private suspend fun buildDeleteMemoRefreshPlan(
            path: String,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            mode: S3LocalSyncMode,
        ): S3MemoRefreshPlan =
            if (isMemoPath(path, layout, mode)) {
                S3MemoRefreshPlan.Full
            } else {
                S3MemoRefreshPlan.None
            }
    }

internal sealed interface S3ActionExecutionState {
    data object Skipped : S3ActionExecutionState

    data class Applied(
        val localChanged: Boolean,
        val remoteChanged: Boolean,
        val updatedLocalFile: LocalS3File? = null,
        val deletedLocalPath: String? = null,
        val updatedRemoteFile: RemoteS3File? = null,
        val deletedRemotePath: String? = null,
        val memoRefreshPlan: S3MemoRefreshPlan = S3MemoRefreshPlan.None,
    ) : S3ActionExecutionState

    data class Failed(
        val path: String,
    ) : S3ActionExecutionState
}

internal sealed interface S3MemoRefreshPlan {
    data object None : S3MemoRefreshPlan

    data class Targets(
        val filenames: Set<String>,
    ) : S3MemoRefreshPlan {
        constructor(filename: String) : this(setOf(filename))
    }

    data object Full : S3MemoRefreshPlan
}

private fun S3SyncAction.operationName(): String =
    when (direction) {
        S3SyncDirection.UPLOAD -> "upload"
        S3SyncDirection.DOWNLOAD -> "download"
        S3SyncDirection.DELETE_LOCAL -> "delete local"
        S3SyncDirection.DELETE_REMOTE -> "delete remote"
        S3SyncDirection.NONE -> "sync"
        S3SyncDirection.CONFLICT -> "conflict"
    }
