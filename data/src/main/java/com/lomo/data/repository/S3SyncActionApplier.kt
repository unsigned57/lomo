package com.lomo.data.repository

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3RemoteVerificationLevel
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
        private val transferWorkspace: S3SyncTransferWorkspace,
    ) {
        internal constructor(
            runtime: S3SyncRepositoryContext,
            encodingSupport: S3SyncEncodingSupport,
            fileBridge: S3SyncFileBridge,
        ) : this(runtime, encodingSupport, fileBridge, S3SyncTransferWorkspace.systemTemp())

        internal suspend fun applyAction(
            action: S3SyncAction,
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            localFiles: Map<String, LocalS3File>,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            verifiedMissingRemotePaths: Set<String>,
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
                            verifiedMissingRemotePaths = verifiedMissingRemotePaths,
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

                    S3SyncDirection.DELETE_LOCAL ->
                        deleteLocalAction(
                            action = action,
                            client = client,
                            layout = layout,
                            config = config,
                            remoteFiles = remoteFiles,
                            metadataByPath = metadataByPath,
                            verifiedMissingRemotePaths = verifiedMissingRemotePaths,
                            fileBridgeScope = fileBridgeScope,
                            mode = mode,
                        )
                    S3SyncDirection.DELETE_REMOTE ->
                        deleteRemoteAction(
                            action = action,
                            client = client,
                            config = config,
                            remoteFiles = remoteFiles,
                            metadataByPath = metadataByPath,
                            verifiedMissingRemotePaths = verifiedMissingRemotePaths,
                        )
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
            verifiedMissingRemotePaths: Set<String>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ActionExecutionState {
            runtime.stateHolder.state.value = S3SyncState.Uploading
            val uploadTarget =
                verifyUploadTarget(
                    action = action,
                    client = client,
                    config = config,
                    remoteFiles = remoteFiles,
                    metadataByPath = metadataByPath,
                    verifiedMissingRemotePaths = verifiedMissingRemotePaths,
                ) ?: return S3ActionExecutionState.Skipped
            return transferWorkspace.withSession { session ->
                val source =
                    fileBridgeScope.exportLocalFile(action.path, layout, session)
                        ?: run {
                            Timber.w("Local file missing during upload: %s, skipping", action.path)
                            return@withSession S3ActionExecutionState.Skipped
                        }
                val uploadFile = encodingSupport.prepareUploadFile(source, config, session)
                val uploaded =
                    client.putObjectFile(
                        key = uploadTarget.remotePath,
                        file = uploadFile.file,
                        contentType = contentTypeForPath(action.path, layout, runtime, mode),
                        metadata = encodingSupport.objectMetadata(localFiles[action.path]?.lastModified),
                    )
                S3ActionExecutionState.Applied(
                    localChanged = false,
                    remoteChanged = true,
                    syncedContentFingerprint = memoContentFingerprint(action.path, layout, mode, source.file),
                    updatedRemoteFile =
                        RemoteS3File(
                            path = action.path,
                            etag = uploaded.eTag,
                            lastModified = localFiles[action.path]?.lastModified,
                            size = localFiles[action.path]?.size ?: source.file.length(),
                            remotePath = uploadTarget.remotePath,
                        ),
                )
            }
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
            return transferWorkspace.withSession { session ->
                val downloadedFile = session.createTempFile("s3-download-", action.path.transferSuffix())
                client.getObjectToFile(remotePath, downloadedFile)
                val decoded = encodingSupport.decodeDownloadedFile(downloadedFile, config, session)
                fileBridgeScope.importLocalFile(action.path, decoded.file, layout)
                val updatedLocalFile = fileBridgeScope.localFile(action.path, layout)
                S3ActionExecutionState.Applied(
                    localChanged = true,
                    remoteChanged = false,
                    syncedContentFingerprint = memoContentFingerprint(action.path, layout, mode, decoded.file),
                    updatedLocalFile = updatedLocalFile,
                    memoRefreshPlan = buildMemoRefreshPlan(action.path, layout, mode),
                )
            }
        }

        private suspend fun deleteLocalAction(
            action: S3SyncAction,
            client: com.lomo.data.s3.LomoS3Client,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            verifiedMissingRemotePaths: Set<String>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ActionExecutionState {
            runtime.stateHolder.state.value = S3SyncState.Deleting
            val remotePath = resolveRemotePath(action.path, remoteFiles, metadataByPath, config)
            if (action.path !in verifiedMissingRemotePaths && client.getObjectMetadata(remotePath) != null) {
                return S3ActionExecutionState.Skipped
            }
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
            verifiedMissingRemotePaths: Set<String>,
        ): S3ActionExecutionState {
            runtime.stateHolder.state.value = S3SyncState.Deleting
            val verification =
                verifyDeleteRemoteTarget(
                    action = action,
                    client = client,
                    config = config,
                    remoteFiles = remoteFiles,
                    metadataByPath = metadataByPath,
                    verifiedMissingRemotePaths = verifiedMissingRemotePaths,
                )
            when (verification) {
                RemoteVerificationConflict -> return S3ActionExecutionState.Skipped
                is RemoteVerificationMissing ->
                    return S3ActionExecutionState.Applied(
                        localChanged = false,
                        remoteChanged = true,
                        deletedRemotePath = action.path,
                    )

                is RemoteVerificationSuccess ->
                    client.deleteObject(verification.remotePath)
            }
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

        private suspend fun verifyUploadTarget(
            action: S3SyncAction,
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            verifiedMissingRemotePaths: Set<String>,
        ): RemoteVerificationSuccess? {
            val remotePath = resolveRemotePath(action.path, remoteFiles, metadataByPath, config)
            val cachedRemote = remoteFiles[action.path]
            val metadataSnapshot = metadataByPath[action.path]
            return when {
                action.path in verifiedMissingRemotePaths ->
                    RemoteVerificationSuccess(remotePath = remotePath, remoteFile = null)

                cachedRemote?.verified == true ->
                    RemoteVerificationSuccess(remotePath = remotePath, remoteFile = cachedRemote)

                cachedRemote == null && metadataSnapshot == null ->
                    RemoteVerificationSuccess(remotePath = remotePath, remoteFile = null)

                else ->
                    verifyUploadTargetAgainstRemote(
                        action = action,
                        client = client,
                        remotePath = remotePath,
                        cachedRemote = cachedRemote,
                        metadataSnapshot = metadataSnapshot,
                    )
            }
        }

        private suspend fun verifyDeleteRemoteTarget(
            action: S3SyncAction,
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            verifiedMissingRemotePaths: Set<String>,
        ): RemoteVerificationResult {
            val remotePath = resolveRemotePath(action.path, remoteFiles, metadataByPath, config)
            val cachedRemote = remoteFiles[action.path]
            if (cachedRemote?.verified == true) {
                return RemoteVerificationSuccess(remotePath = remotePath, remoteFile = cachedRemote)
            }
            if (action.path in verifiedMissingRemotePaths) {
                return RemoteVerificationMissing(remotePath)
            }
            val verifiedRemote = client.getObjectMetadata(remotePath)?.toVerifiedRemoteFile(action.path)
            return when {
                verifiedRemote == null -> RemoteVerificationMissing(remotePath)
                cachedRemote == null -> RemoteVerificationSuccess(remotePath = remotePath, remoteFile = verifiedRemote)
                verifiedRemote.matchesCached(cachedRemote) ->
                    RemoteVerificationSuccess(remotePath = remotePath, remoteFile = verifiedRemote)

                else -> RemoteVerificationConflict
            }
        }

        private suspend fun buildMemoRefreshPlan(
            path: String,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            mode: S3LocalSyncMode,
        ): S3MemoRefreshPlan {
            val target = resolveMemoRefreshTarget(path, layout, mode) ?: return S3MemoRefreshPlan.None
            return S3MemoRefreshPlan.Targets(target)
        }

        private suspend fun buildDeleteMemoRefreshPlan(
            path: String,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            mode: S3LocalSyncMode,
        ): S3MemoRefreshPlan =
            resolveMemoRefreshTarget(path, layout, mode)?.let(S3MemoRefreshPlan::Targets)
                ?: S3MemoRefreshPlan.None

        private fun memoContentFingerprint(
            path: String,
            layout: com.lomo.data.sync.SyncDirectoryLayout,
            mode: S3LocalSyncMode,
            file: java.io.File,
        ): String? =
            if (isMemoPath(path, layout, mode)) {
                file.md5Hex()
            } else {
                null
            }
    }

internal sealed interface S3ActionExecutionState {
    data object Skipped : S3ActionExecutionState

    data class Applied(
        val localChanged: Boolean,
        val remoteChanged: Boolean,
        val syncedContentFingerprint: String? = null,
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

private sealed interface RemoteVerificationResult

private data class RemoteVerificationSuccess(
    val remotePath: String,
    val remoteFile: RemoteS3File?,
) : RemoteVerificationResult

private data class RemoteVerificationMissing(
    val remotePath: String,
) : RemoteVerificationResult

private data object RemoteVerificationConflict : RemoteVerificationResult

private fun com.lomo.data.s3.S3RemoteObject.toVerifiedRemoteFile(path: String): RemoteS3File =
    RemoteS3File(
        path = path,
        etag = eTag,
        lastModified = lastModified,
        remotePath = key,
        verificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
    )

private fun RemoteS3File.matchesCached(cached: RemoteS3File): Boolean =
    remotePath == cached.remotePath &&
        etag == cached.etag &&
        lastModified == cached.lastModified

private fun RemoteS3File.matchesMetadataSnapshot(
    metadata: com.lomo.data.local.entity.S3SyncMetadataEntity,
): Boolean =
    remotePath == metadata.remotePath &&
        etag == metadata.etag &&
        lastModified == metadata.remoteLastModified

private suspend fun verifyUploadTargetAgainstRemote(
    action: S3SyncAction,
    client: com.lomo.data.s3.LomoS3Client,
    remotePath: String,
    cachedRemote: RemoteS3File?,
    metadataSnapshot: com.lomo.data.local.entity.S3SyncMetadataEntity?,
): RemoteVerificationSuccess? {
    val verifiedRemote = client.getObjectMetadata(remotePath)?.toVerifiedRemoteFile(action.path)
    return when {
        verifiedRemote == null ->
            RemoteVerificationSuccess(
                remotePath = remotePath,
                remoteFile = null,
            )

        cachedRemote != null && verifiedRemote.matchesCached(cachedRemote) ->
            RemoteVerificationSuccess(
                remotePath = remotePath,
                remoteFile = verifiedRemote,
            )

        metadataSnapshot != null && verifiedRemote.matchesMetadataSnapshot(metadataSnapshot) ->
            RemoteVerificationSuccess(
                remotePath = remotePath,
                remoteFile = verifiedRemote,
            )

        else -> null
    }
}

private fun String.transferSuffix(): String =
    substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" } ?: ".tmp"
