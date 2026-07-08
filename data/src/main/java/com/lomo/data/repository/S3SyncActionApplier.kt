package com.lomo.data.repository
import com.lomo.data.util.sanitizePathForLog
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncFailureException
import com.lomo.domain.model.S3SyncState
import timber.log.Timber
class S3SyncActionApplier
constructor(
        private val runtime: S3SyncRepositoryContext,
        private val encodingSupport: S3SyncEncodingSupport,
        private val objectKeyPolicy: S3RemoteObjectKeyPolicy,
        fileBridge: S3SyncFileBridge,
        private val transferWorkspace: S3SyncTransferWorkspace,
    ) {
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
            observedMissingRemotePaths: Set<String> = emptySet(),
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
                            observedMissingRemotePaths = observedMissingRemotePaths,
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
                            observedMissingRemotePaths = observedMissingRemotePaths,
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
                            observedMissingRemotePaths = observedMissingRemotePaths,
                        )
                    S3SyncDirection.NONE,
                    S3SyncDirection.CONFLICT,
                    -> S3ActionExecutionState.Skipped
                }
            }.onFailure { error ->
                if (error is S3SyncFailureException) {
                    throw error
                }
            }.getOrElse { error ->
                Timber.e(error, "Failed to %s %s", action.operationName(), sanitizePathForLog(action.path))
                S3ActionExecutionState.Failed(action.path)
            }
        internal suspend fun applyDeleteRemoteActions(
            actions: List<S3SyncAction>,
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            verifiedMissingRemotePaths: Set<String>,
            observedMissingRemotePaths: Set<String> = emptySet(),
        ): Map<String, S3ActionExecutionState> {
            if (actions.isEmpty()) {
                return emptyMap()
            }
            runtime.stateHolder.state.value = S3SyncState.Deleting
            val verifiedTargets = mutableListOf<DeleteRemoteBatchTarget>()
            val states = linkedMapOf<String, S3ActionExecutionState>()
            actions.forEach { action ->
                val verification =
                    runNonFatalCatching {
                        verifyDeleteRemoteTarget(
                            action = action,
                            client = client,
                            config = config,
                            remoteFiles = remoteFiles,
                            metadataByPath = metadataByPath,
                            verifiedMissingRemotePaths = verifiedMissingRemotePaths,
                            observedMissingRemotePaths = observedMissingRemotePaths,
                        )
                    }.onFailure { error ->
                        if (error is S3SyncFailureException) {
                            throw error
                        }
                    }.getOrElse { error ->
                        Timber.e(error, "Failed to verify delete remote %s", sanitizePathForLog(action.path))
                        states[action.path] = S3ActionExecutionState.Failed(action.path)
                        return@forEach
                    }
                when (verification) {
                    RemoteVerificationConflict ->
                        states[action.path] = S3ActionExecutionState.Skipped
                    is RemoteVerificationMissing ->
                        states[action.path] = action.deletedRemoteApplied()
                    is RemoteVerificationSuccess ->
                        verifiedTargets += DeleteRemoteBatchTarget(action = action, remoteKey = verification.remoteKey)
                }
            }
            if (verifiedTargets.isEmpty()) {
                return states
            }
            val failedKeys =
                runNonFatalCatching {
                    client.deleteObjects(verifiedTargets.map { target -> target.remoteKey.value }).failedKeys
                }.onFailure { error ->
                    if (error is S3SyncFailureException) {
                        throw error
                    }
                }.getOrElse { error ->
                    Timber.e(error, "Failed to batch delete %d S3 object(s)", verifiedTargets.size)
                    verifiedTargets.forEach { target ->
                        states[target.action.path] = S3ActionExecutionState.Failed(target.action.path)
                    }
                    return states
                }
            verifiedTargets.forEach { target ->
                states[target.action.path] =
                    if (target.remoteKey.value in failedKeys) {
                        S3ActionExecutionState.Failed(target.action.path)
                    } else {
                        target.action.deletedRemoteApplied()
                    }
            }
            return states
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
            observedMissingRemotePaths: Set<String>,
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
                    observedMissingRemotePaths = observedMissingRemotePaths,
                ) ?: return S3ActionExecutionState.Skipped
            return transferWorkspace.withSession { session ->
                val source =
                    fileBridgeScope.exportLocalFile(action.path, layout, session)
                        ?: run {
                            Timber.w("Local file missing during upload: %s, skipping", sanitizePathForLog(action.path))
                            return@withSession S3ActionExecutionState.Skipped
                        }
                val uploadFile = encodingSupport.prepareUploadFile(source, config, session)
                val syncedContentFingerprint = source.file.md5Hex()
                val ifNoneMatch = if (config.endpointProfile.conditionalWritesSupported &&
                    uploadTarget.remoteFile == null) "*" else null
                val ifMatch = if (config.endpointProfile.conditionalWritesSupported &&
                    uploadTarget.remoteFile != null) uploadTarget.remoteFile.etag else null
                val uploaded =
                    client.putObjectFile(
                        key = uploadTarget.remoteKey.value,
                        file = uploadFile.file,
                        contentType = contentTypeForPath(action.path, layout, runtime, mode),
                        metadata =
                            encodingSupport.objectMetadata(
                                lastModified = localFiles[action.path]?.lastModified,
                                contentMd5 = syncedContentFingerprint,
                            ),
                        ifMatch = ifMatch,
                        ifNoneMatch = ifNoneMatch,
                    )
                if (uploaded.conditionalWriteFailed) {
                    return@withSession S3ActionExecutionState.Conflict(action.path)
                }
                S3ActionExecutionState.Applied(
                    localChanged = false,
                    remoteChanged = true,
                    syncedContentFingerprint = syncedContentFingerprint,
                    updatedRemoteFile =
                        RemoteS3File(
                            path = action.path,
                            etag = uploaded.eTag,
                            lastModified = localFiles[action.path]?.lastModified,
                            size = localFiles[action.path]?.size ?: source.file.length(),
                            contentMd5 = syncedContentFingerprint,
                            remotePath = uploadTarget.remoteKey.value,
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
            val remoteKey = resolveRemoteKey(action.path, remoteFiles, metadataByPath, config)
            return transferWorkspace.withSession { session ->
                val downloadedFile = session.createTempFile("s3-download-", action.path.transferSuffix())
                client.getObjectToFile(remoteKey.value, downloadedFile)
                val decoded = encodingSupport.decodeDownloadedFile(downloadedFile, config, session)
                fileBridgeScope.importLocalFile(action.path, decoded.file, layout)
                val updatedLocalFile = fileBridgeScope.localFile(action.path, layout)
                S3ActionExecutionState.Applied(
                    localChanged = true,
                    remoteChanged = false,
                    syncedContentFingerprint = decoded.file.md5Hex(),
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
            observedMissingRemotePaths: Set<String>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ActionExecutionState {
            runtime.stateHolder.state.value = S3SyncState.Deleting
            val remoteKey = resolveRemoteKey(action.path, remoteFiles, metadataByPath, config)
            val knownMissing =
                action.path in verifiedMissingRemotePaths || action.path in observedMissingRemotePaths
            if (!knownMissing && client.getObjectMetadata(remoteKey.value) != null) {
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
            observedMissingRemotePaths: Set<String>,
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
                    observedMissingRemotePaths = observedMissingRemotePaths,
                )
            when (verification) {
                RemoteVerificationConflict -> return S3ActionExecutionState.Skipped
                is RemoteVerificationMissing ->
                    return action.deletedRemoteApplied()
                is RemoteVerificationSuccess ->
                    client.deleteObject(verification.remoteKey.value)
            }
            return action.deletedRemoteApplied()
        }
        private fun resolveRemoteKey(
            relativePath: String,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            config: S3ResolvedConfig,
        ): S3RemoteObjectKey =
            objectKeyPolicy.resolveOperationKey(
                relativePath = relativePath,
                config = config,
                remoteFile = remoteFiles[relativePath],
                metadata = metadataByPath[relativePath],
            )
        private suspend fun verifyUploadTarget(
            action: S3SyncAction,
            client: com.lomo.data.s3.LomoS3Client,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            verifiedMissingRemotePaths: Set<String>,
            observedMissingRemotePaths: Set<String>,
        ): RemoteVerificationSuccess? {
            val remoteKey = resolveRemoteKey(action.path, remoteFiles, metadataByPath, config)
            val cachedRemote = remoteFiles[action.path]
            val metadataSnapshot = metadataByPath[action.path]
            return when {
                action.path in verifiedMissingRemotePaths ||
                    action.path in observedMissingRemotePaths ->
                    RemoteVerificationSuccess(remoteKey = remoteKey, remoteFile = null)
                cachedRemote?.verified == true ->
                    RemoteVerificationSuccess(remoteKey = remoteKey, remoteFile = cachedRemote)
                config.endpointProfile.conditionalWritesSupported &&
                    cachedRemote != null ->
                    RemoteVerificationSuccess(remoteKey = remoteKey, remoteFile = cachedRemote)
                config.endpointProfile.conditionalWritesSupported &&
                    cachedRemote == null &&
                    metadataSnapshot != null ->
                    RemoteVerificationSuccess(
                        remoteKey = remoteKey,
                        remoteFile = metadataSnapshot.toConditionalWriteRemoteFile(action.path),
                    )
                cachedRemote == null && metadataSnapshot == null ->
                    RemoteVerificationSuccess(remoteKey = remoteKey, remoteFile = null)
                else ->
                    verifyUploadTargetAgainstRemote(
                        action = action,
                        client = client,
                        remoteKey = remoteKey,
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
            observedMissingRemotePaths: Set<String>,
        ): RemoteVerificationResult {
            val remoteKey = resolveRemoteKey(action.path, remoteFiles, metadataByPath, config)
            val cachedRemote = remoteFiles[action.path]
            if (cachedRemote?.verified == true) {
                return RemoteVerificationSuccess(remoteKey = remoteKey, remoteFile = cachedRemote)
            }
            if (action.path in verifiedMissingRemotePaths || action.path in observedMissingRemotePaths) {
                return RemoteVerificationMissing(remoteKey)
            }
            val verifiedRemote = client.getObjectMetadata(remoteKey.value)?.toVerifiedRemoteFile(action.path)
            return when {
                verifiedRemote == null -> RemoteVerificationMissing(remoteKey)
                cachedRemote == null -> RemoteVerificationSuccess(remoteKey = remoteKey, remoteFile = verifiedRemote)
                verifiedRemote.matchesCached(cachedRemote) ->
                    RemoteVerificationSuccess(remoteKey = remoteKey, remoteFile = verifiedRemote)
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
    }
internal sealed interface S3ActionExecutionState {
    data object Skipped : S3ActionExecutionState
    data class Conflict(
        val path: String,
    ) : S3ActionExecutionState
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
    val remoteKey: S3RemoteObjectKey,
    val remoteFile: RemoteS3File?,
) : RemoteVerificationResult
private data class RemoteVerificationMissing(
    val remoteKey: S3RemoteObjectKey,
) : RemoteVerificationResult
private data object RemoteVerificationConflict : RemoteVerificationResult
private data class DeleteRemoteBatchTarget(
    val action: S3SyncAction,
    val remoteKey: S3RemoteObjectKey,
)
private fun S3SyncAction.deletedRemoteApplied(): S3ActionExecutionState.Applied =
    S3ActionExecutionState.Applied(
        localChanged = false,
        remoteChanged = true,
        deletedRemotePath = path,
    )
private fun com.lomo.data.s3.S3RemoteObject.toVerifiedRemoteFile(path: String): RemoteS3File =
    RemoteS3File(
        path = path,
        etag = eTag,
        lastModified = resolveRemoteObjectLastModified(metadata, lastModified),
        contentMd5 = null,
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
private fun com.lomo.data.local.entity.S3SyncMetadataEntity.toConditionalWriteRemoteFile(
    path: String,
): RemoteS3File =
    RemoteS3File(
        path = path,
        etag = etag,
        lastModified = remoteLastModified,
        size = remoteSize,
        remotePath = remotePath,
        verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
    )
private suspend fun verifyUploadTargetAgainstRemote(
    action: S3SyncAction,
    client: com.lomo.data.s3.LomoS3Client,
    remoteKey: S3RemoteObjectKey,
    cachedRemote: RemoteS3File?,
    metadataSnapshot: com.lomo.data.local.entity.S3SyncMetadataEntity?,
): RemoteVerificationSuccess? {
    val verifiedRemote = client.getObjectMetadata(remoteKey.value)?.toVerifiedRemoteFile(action.path)
    return when {
        verifiedRemote == null ->
            RemoteVerificationSuccess(
                remoteKey = remoteKey,
                remoteFile = null,
            )
        cachedRemote != null && verifiedRemote.matchesCached(cachedRemote) ->
            RemoteVerificationSuccess(
                remoteKey = remoteKey,
                remoteFile = verifiedRemote,
            )
        metadataSnapshot != null && verifiedRemote.matchesMetadataSnapshot(metadataSnapshot) ->
            RemoteVerificationSuccess(
                remoteKey = remoteKey,
                remoteFile = verifiedRemote,
            )
        else -> null
    }
}
private fun String.transferSuffix(): String =
    substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" } ?: ".tmp"
