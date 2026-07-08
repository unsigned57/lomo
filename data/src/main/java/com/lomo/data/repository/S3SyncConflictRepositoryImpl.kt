package com.lomo.data.repository
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncConflictTextMerge
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.S3SyncConflictRepository
import com.lomo.domain.repository.S3SyncReviewRepository
import timber.log.Timber
class S3SyncConflictRepositoryImpl
constructor(
        private val resolver: S3ConflictResolver,
    ) : S3SyncConflictRepository {
        override suspend fun resolveConflicts(
            resolution: SyncConflictResolution,
            conflictSet: SyncConflictSet,
        ): S3SyncResult = resolver.resolveConflicts(resolution, conflictSet)
    }
class S3SyncReviewRepositoryImpl
constructor(
        private val resolver: S3ReviewResolver,
    ) : S3SyncReviewRepository {
        override suspend fun resolveReview(
            resolution: SyncReviewResolution,
            review: SyncReviewSession,
        ): S3SyncResult = resolver.resolveReview(resolution, review)
    }
class S3ConflictResolver
    internal constructor(
        private val runtime: S3SyncRepositoryContext,
        private val support: S3SyncRepositorySupport,
        private val encodingSupport: S3SyncEncodingSupport,
        private val objectKeyPolicy: S3RemoteObjectKeyPolicy,
        private val fileBridge: S3SyncFileBridge,
        private val protocolStateStore: S3SyncProtocolStateStore,
        private val localChangeJournalStore: S3LocalChangeJournalStore,
        private val remoteIndexStore: S3RemoteIndexStore,
        private val pendingConflictStore: PendingSyncConflictStore,
        private val transferWorkspace: S3SyncTransferWorkspace,
        private val lifecycleRunner: RemoteSyncLifecycleRunner,
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
                    lifecycleRunner.run(
                        S3ResolutionLifecycleStages(
                            client = client,
                            workItemCount = conflictSet.files.size,
                            resolve = { meteredClient ->
                                resolveConflictsWithClient(
                                    resolution = resolution,
                                    conflictSet = conflictSet,
                                    client = meteredClient,
                                    layout = layout,
                                    config = config,
                                    fileBridgeScope = fileBridgeScope,
                                    mode = mode,
                                )
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
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3SyncResult {
            val context = loadResolutionContext(conflictSet)
            val pendingDescriptor = pendingConflictStore.readDescriptor(conflictSet.source)
            validatePendingConflictBeforeResolve(
                descriptor = pendingDescriptor,
                client = client,
                layout = layout,
                config = config,
                metadataByPath = context.metadataByPath,
                fileBridgeScope = fileBridgeScope,
            )?.let { reason ->
                pendingConflictStore.clear(conflictSet.source)
                return S3SyncResult.Error("Pending S3 conflict session requires rebuild: $reason")
            }
            val applied =
                applyChoices(
                    resolution = resolution,
                    conflictSet = conflictSet,
                    client = client,
                    layout = layout,
                    config = config,
                    remoteFiles =
                        context.remoteFiles +
                            pendingDescriptor
                                ?.toRestoredRemoteFiles(
                                    config = config,
                                    objectKeyPolicy = objectKeyPolicy,
                                ).orEmpty(),
                    metadataByPath = context.metadataByPath,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                )
            if (applied.hasResolvedActions()) {
                runtime.transactionRunner.runInTransaction {
                    fileBridge.persistMetadata(
                        localFiles = applied.resolvedLocalFiles,
                        remoteFiles = applied.resolvedRemoteFiles,
                        metadataByPath = context.metadataByPath,
                        actionOutcomes = applied.actionOutcomes,
                        unresolvedPaths = applied.unresolvedPaths(),
                        completeSnapshot = false,
                    )
                    commitIncrementalS3ResolutionState(
                        protocolStateStore = protocolStateStore,
                        localChangeJournalStore = localChangeJournalStore,
                        remoteIndexStore = remoteIndexStore,
                        layout = layout,
                        mode = mode,
                        protocolState = context.protocolState,
                        resolvedRemoteFiles = applied.resolvedRemoteFiles,
                        resolvedPaths = applied.actionOutcomes.keys,
                    )
                }
                refreshS3ResolutionMemoCache(runtime, "conflict")
            }
            return buildFinalResult(conflictSet, pendingDescriptor, applied.unresolvedFiles)
        }
        private suspend fun validatePendingConflictBeforeResolve(
            descriptor: PendingSyncConflictDescriptor?,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
        ): PendingSyncInvalidationReason? {
            descriptor ?: return null
            descriptor.files.forEach { file ->
                val local =
                    fileBridgeScope.pendingValidationLocalFile(
                        path = file.relativePath,
                        layout = layout,
                        requireContentFingerprint = file.local.contentHash != null,
                    ) ?: return PendingSyncInvalidationReason.MISSING_LOCAL
                val remoteKey =
                    objectKeyPolicy.resolveS3OperationKey(
                        relativePath = file.relativePath,
                        explicitExistingKey = file.remote.locator,
                        remoteFile = null,
                        metadata = metadataByPath[file.relativePath],
                        config = config,
                    )
                if (file.isBinary) {
                    // Binary/non-memo files carry no comparable text: validate through the recorded
                    // fingerprint and remote object metadata, rejecting local divergence before any HEAD.
                    if (!file.local.matchesLocal(local)) return PendingSyncInvalidationReason.STALE_LOCAL
                    if (!file.remote.hasCompleteRemoteMetadata()) return PendingSyncInvalidationReason.STALE_REMOTE
                    val remote =
                        client.getObjectMetadata(remoteKey.value)
                            ?: return PendingSyncInvalidationReason.MISSING_REMOTE
                    val remoteLastModified =
                        encodingSupport.resolveRemoteLastModified(remote.metadata, remote.lastModified)
                    if (!file.remote.matchesRemote(remote.eTag, remoteLastModified, remote.size)) {
                        return PendingSyncInvalidationReason.STALE_REMOTE
                    }
                } else {
                    // Memo/text conflicts validate staleness by content equality, the authoritative signal
                    // for "the versions the user is resolving are still current". Remote `lastModified` is
                    // normalized differently between the cached remote index (raw HTTP time) and a fresh
                    // HEAD (app-embedded mtime), so an exact timestamp/etag match would spuriously
                    // invalidate an unchanged memo and force the user to apply the resolution twice.
                    client.getObjectMetadata(remoteKey.value)
                        ?: return PendingSyncInvalidationReason.MISSING_REMOTE
                    val localContent = fileBridgeScope.readLocalText(file.relativePath, layout)
                    val remoteContent =
                        String(
                            encodingSupport.decodeContent(client.getSmallObject(remoteKey.value).bytes, config),
                            Charsets.UTF_8,
                        )
                    if (!file.local.matchesContent(localContent)) return PendingSyncInvalidationReason.STALE_LOCAL
                    if (!file.remote.matchesContent(remoteContent)) return PendingSyncInvalidationReason.STALE_REMOTE
                }
            }
            return null
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
                        config = config,
                        remoteFiles = remoteFiles,
                        metadataByPath = metadataByPath,
                        fileBridgeScope = fileBridgeScope,
                        mode = mode,
                    )?.let { FileConflictApplication.Applied(it) }
                        ?: FileConflictApplication.Unresolved as FileConflictApplication<S3ConflictResolutionOutcome>
                }
            batch.appliedChoices.forEach { applied ->
                val resolved = applied.value
                resolved.localFile?.let { localFile ->
                    resolvedLocalFiles[resolved.path] = localFile
                }
                resolvedRemoteFiles[resolved.path] = resolved.remoteFile
            }
            val actionOutcomes =
                batch.appliedChoices.associate { applied ->
                    applied.path to (applied.value.direction to applied.value.reason)
                }
            return S3AppliedConflictResolution(
                resolvedLocalFiles = resolvedLocalFiles,
                resolvedRemoteFiles = resolvedRemoteFiles,
                unresolvedFiles = batch.unresolvedFiles,
                actionOutcomes = actionOutcomes,
            )
        }
        private suspend fun buildFinalResult(
            conflictSet: SyncConflictSet,
            pendingDescriptor: PendingSyncConflictDescriptor?,
            unresolvedFiles: List<com.lomo.domain.model.SyncConflictFile>,
        ): S3SyncResult {
            if (unresolvedFiles.isNotEmpty()) {
                val pendingConflicts = conflictSet.copy(files = unresolvedFiles)
                val pendingDescriptorTail =
                    requireNotNull(pendingDescriptor) {
                        "S3 unresolved conflict persistence requires a pending descriptor with provider locators"
                    }.filterFiles(unresolvedFiles.map { file -> file.relativePath }.toSet())
                pendingConflictStore.writeDescriptor(pendingDescriptorTail)
                runtime.stateHolder.state.value = com.lomo.domain.model.S3SyncState.ConflictDetected(pendingConflicts)
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
        ): S3ConflictResolutionOutcome? {
            val mergedText =
                SyncConflictTextMerge.merge(
                    localText = file.localContent,
                    remoteText = file.remoteContent,
                    localLastModified = file.localLastModified,
                    remoteLastModified = file.remoteLastModified,
                ) ?: return null
            val mergedBytes = mergedText.toByteArray(Charsets.UTF_8)
            fileBridgeScope.writeLocalBytes(file.relativePath, mergedBytes, layout)
            val localFile = fileBridgeScope.localFile(file.relativePath, layout)
            val remoteKey =
                objectKeyPolicy.resolveS3OperationKey(
                    relativePath = file.relativePath,
                    remoteFile = remoteFiles[file.relativePath],
                    metadata = metadataByPath[file.relativePath],
                    config = config,
                )
            val uploaded =
                client.putSmallObject(
                    key = remoteKey.value,
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
                        remotePath = remoteKey.value,
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
            return transferWorkspace.withSession { session ->
                val source =
                    fileBridgeScope.exportLocalFile(file.relativePath, layout, session)
                        ?: file.localContent?.let { content ->
                            S3TransferFile(
                                session.createTempFile("s3-conflict-local-", ".txt").apply {
                                    writeText(content, Charsets.UTF_8)
                                },
                            )
                        }
                        ?: return@withSession null
                val localFile = fileBridgeScope.localFile(file.relativePath, layout)
                val remoteKey =
                    objectKeyPolicy.resolveS3OperationKey(
                        relativePath = file.relativePath,
                        remoteFile = remoteFiles[file.relativePath],
                        metadata = metadataByPath[file.relativePath],
                        config = config,
                    )
                val uploadFile = encodingSupport.prepareUploadFile(source, config, session)
                val uploaded =
                    client.putObjectFile(
                        key = remoteKey.value,
                        file = uploadFile.file,
                        contentType = contentTypeForPath(file.relativePath, layout, runtime, mode),
                        metadata = encodingSupport.objectMetadata(System.currentTimeMillis()),
                    )
                S3ConflictResolutionOutcome(
                    path = file.relativePath,
                    localFile = localFile,
                    remoteFile =
                        RemoteS3File(
                            path = file.relativePath,
                            etag = uploaded.eTag,
                            lastModified = localFile?.lastModified ?: System.currentTimeMillis(),
                            remotePath = remoteKey.value,
                        ),
                    direction = com.lomo.domain.model.S3SyncDirection.UPLOAD,
                    reason = com.lomo.domain.model.S3SyncReason.LOCAL_NEWER,
                )
            }
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
            val remoteKey =
                objectKeyPolicy.resolveS3OperationKey(
                    relativePath = file.relativePath,
                    remoteFile = remoteFiles[file.relativePath],
                    metadata = metadataByPath[file.relativePath],
                    config = config,
                )
            return transferWorkspace.withSession { session ->
                val downloadedFile = session.createTempFile("s3-conflict-remote-", ".tmp")
                val remoteObject = client.getObjectToFile(remoteKey.value, downloadedFile)
                val decoded = encodingSupport.decodeDownloadedFile(downloadedFile, config, session)
                fileBridgeScope.importLocalFile(file.relativePath, decoded.file, layout)
                val localFile = fileBridgeScope.localFile(file.relativePath, layout)
                S3ConflictResolutionOutcome(
                    path = file.relativePath,
                    localFile = localFile,
                    remoteFile =
                        RemoteS3File(
                            path = file.relativePath,
                            etag = remoteObject.eTag ?: remoteFiles[file.relativePath]?.etag,
                            lastModified =
                                encodingSupport.resolveRemoteLastModified(
                                    remoteObject.metadata,
                                    remoteObject.lastModified,
                                ) ?: remoteFiles[file.relativePath]?.lastModified,
                            remotePath = remoteKey.value,
                        ),
                    direction = com.lomo.domain.model.S3SyncDirection.DOWNLOAD,
                    reason = com.lomo.domain.model.S3SyncReason.REMOTE_NEWER,
                )
            }
        }
    }
class S3ReviewResolver
    internal constructor(
        private val runtime: S3SyncRepositoryContext,
        private val support: S3SyncRepositorySupport,
        private val encodingSupport: S3SyncEncodingSupport,
        private val objectKeyPolicy: S3RemoteObjectKeyPolicy,
        private val fileBridge: S3SyncFileBridge,
        private val protocolStateStore: S3SyncProtocolStateStore,
        private val localChangeJournalStore: S3LocalChangeJournalStore,
        private val remoteIndexStore: S3RemoteIndexStore,
        private val pendingReviewStore: PendingSyncReviewStore,
        private val transferWorkspace: S3SyncTransferWorkspace,
        private val lifecycleRunner: RemoteSyncLifecycleRunner,
    ) {
        suspend fun resolveReview(
            resolution: SyncReviewResolution,
            review: SyncReviewSession,
        ): S3SyncResult {
            val config = support.resolveConfig() ?: return support.notConfiguredResult()
            val layout = SyncDirectoryLayout.resolve(runtime.dataStore)
            val mode = resolveLocalSyncMode(runtime)
            val fileBridgeScope = fileBridge.modeAware(mode)
            return runNonFatalCatching {
                support.withClient(config) { client ->
                    lifecycleRunner.run(
                        S3ResolutionLifecycleStages(
                            client = client,
                            workItemCount = review.items.size,
                            resolve = { meteredClient ->
                                resolveReviewWithClient(
                                    resolution = resolution,
                                    review = review,
                                    client = meteredClient,
                                    layout = layout,
                                    config = config,
                                    fileBridgeScope = fileBridgeScope,
                                    mode = mode,
                                )
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
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3SyncResult {
            val context = loadResolutionContext(review)
            val pendingDescriptor = pendingReviewStore.readDescriptor(review.source)
            var pendingRemoteFiles = emptyMap<String, RemoteS3File>()
            val validatedReview =
                when (
                    val restored =
                        pendingDescriptor?.let { descriptor ->
                            S3PendingReviewRestorer(
                                client = client,
                                layout = layout,
                                config = config,
                                metadataByPath = context.metadataByPath,
                                fileBridgeScope = fileBridgeScope,
                                encodingSupport = encodingSupport,
                                objectKeyPolicy = objectKeyPolicy,
                            ).restore(descriptor)
                        }
                ) {
                    null -> review
                    is PendingSyncRestoreResult.Restored -> {
                        pendingRemoteFiles =
                            requireNotNull(pendingDescriptor)
                                .toRestoredRemoteFiles(
                                    config = config,
                                    objectKeyPolicy = objectKeyPolicy,
                                )
                        restored.session
                    }
                    is PendingSyncRestoreResult.Invalidated -> {
                        pendingReviewStore.clear(review.source)
                        return S3SyncResult.Error("Pending S3 review session requires rebuild: ${restored.reason}")
                    }
                    is PendingSyncRestoreResult.Failed ->
                        return S3SyncResult.Error(
                            message = "Pending S3 review session restore failed: ${restored.error.category}",
                            exception = restored.error.cause,
                        )
                }
            val applied =
                applyChoices(
                    resolution = resolution,
                    review = validatedReview,
                    client = client,
                    layout = layout,
                    config = config,
                    remoteFiles = context.remoteFiles + pendingRemoteFiles,
                    metadataByPath = context.metadataByPath,
                    fileBridgeScope = fileBridgeScope,
                    mode = mode,
                )
            if (applied.hasResolvedActions()) {
                runtime.transactionRunner.runInTransaction {
                    fileBridge.persistMetadata(
                        localFiles = applied.resolvedLocalFiles,
                        remoteFiles = applied.resolvedRemoteFiles,
                        metadataByPath = context.metadataByPath,
                        actionOutcomes = applied.actionOutcomes,
                        unresolvedPaths = applied.unresolvedPaths(),
                        completeSnapshot = false,
                    )
                    commitIncrementalS3ResolutionState(
                        protocolStateStore = protocolStateStore,
                        localChangeJournalStore = localChangeJournalStore,
                        remoteIndexStore = remoteIndexStore,
                        layout = layout,
                        mode = mode,
                        protocolState = context.protocolState,
                        resolvedRemoteFiles = applied.resolvedRemoteFiles,
                        resolvedPaths = applied.actionOutcomes.keys,
                    )
                }
                refreshS3ResolutionMemoCache(runtime, "review")
            }
            return buildFinalResult(validatedReview, pendingDescriptor, applied.unresolvedItems)
        }
        private suspend fun loadResolutionContext(
            review: SyncReviewSession,
        ): S3ResolutionContext {
            val protocolState = protocolStateStore.read()
            val reviewPaths = review.items.map { it.relativePath }.distinct().sorted()
            val metadataByPath =
                if (reviewPaths.isEmpty()) {
                    emptyMap()
                } else {
                    runtime.metadataDao.getByRelativePaths(reviewPaths).associateBy { it.relativePath }
                }
            return S3ResolutionContext(
                protocolState = protocolState,
                metadataByPath = metadataByPath,
                remoteFiles = loadRemoteFiles(reviewPaths, metadataByPath),
            )
        }
        private suspend fun loadRemoteFiles(
            reviewPaths: List<String>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
        ): Map<String, RemoteS3File> =
            if (remoteIndexStore.remoteIndexEnabled) {
                remoteIndexStore.readByRelativePaths(reviewPaths)
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
            resolution: SyncReviewResolution,
            review: SyncReviewSession,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3AppliedReviewResolution {
            val resolvedLocalFiles = mutableMapOf<String, LocalS3File>()
            val resolvedRemoteFiles = remoteFiles.toMutableMap()
            val unresolvedItems = mutableListOf<SyncReviewItem>()
            val actionOutcomes =
                mutableMapOf<
                    String,
                    Pair<com.lomo.domain.model.S3SyncDirection, com.lomo.domain.model.S3SyncReason>,
                >()
            review.items.forEach { item ->
                val choice = resolution.perItemChoices[item.relativePath] ?: SyncReviewResolutionChoice.SKIP_FOR_NOW
                if (item.state == SyncReviewItemState.BLOCKED || choice == SyncReviewResolutionChoice.SKIP_FOR_NOW) {
                    unresolvedItems += item
                    return@forEach
                }
                val resolved =
                    applyChoice(
                        item = item,
                        choice = choice,
                        client = client,
                        layout = layout,
                        config = config,
                        remoteFiles = remoteFiles,
                        metadataByPath = metadataByPath,
                        fileBridgeScope = fileBridgeScope,
                        mode = mode,
                    )
                if (resolved == null) {
                    unresolvedItems += item
                } else {
                    resolved.localFile?.let { localFile ->
                        resolvedLocalFiles[resolved.path] = localFile
                    }
                    resolvedRemoteFiles[resolved.path] = resolved.remoteFile
                    actionOutcomes[resolved.path] = resolved.direction to resolved.reason
                }
            }
            return S3AppliedReviewResolution(
                resolvedLocalFiles = resolvedLocalFiles,
                resolvedRemoteFiles = resolvedRemoteFiles,
                unresolvedItems = unresolvedItems,
                actionOutcomes = actionOutcomes,
            )
        }
        private suspend fun buildFinalResult(
            review: SyncReviewSession,
            pendingDescriptor: PendingSyncReviewDescriptor?,
            unresolvedItems: List<SyncReviewItem>,
        ): S3SyncResult {
            if (unresolvedItems.isNotEmpty()) {
                val pendingReview = review.copy(items = unresolvedItems)
                val pendingDescriptorTail =
                    requireNotNull(pendingDescriptor) {
                        "S3 unresolved review persistence requires a pending descriptor with provider locators"
                    }.filterItems(unresolvedItems.map { item -> item.relativePath }.toSet())
                pendingReviewStore.writeDescriptor(pendingDescriptorTail)
                runtime.stateHolder.state.value = S3SyncState.PreviewingInitialSync(pendingReview)
                return S3SyncResult.Review("Pending review items remain", pendingReview)
            }
            pendingReviewStore.clear(review.source)
            val now = System.currentTimeMillis()
            runtime.stateHolder.state.value = S3SyncState.Success(now, "Review resolved")
            return S3SyncResult.Success("Review resolved")
        }
        private suspend fun applyChoice(
            item: SyncReviewItem,
            choice: SyncReviewResolutionChoice,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ReviewResolutionOutcome? =
            when (choice) {
                SyncReviewResolutionChoice.KEEP_LOCAL ->
                    uploadLocalVersion(item, client, layout, config, remoteFiles, metadataByPath, fileBridgeScope, mode)
                SyncReviewResolutionChoice.KEEP_INCOMING ->
                    downloadIncomingVersion(item, client, layout, config, remoteFiles, metadataByPath, fileBridgeScope)
                SyncReviewResolutionChoice.MERGE_TEXT ->
                    mergeTextVersion(item, client, layout, config, remoteFiles, metadataByPath, fileBridgeScope, mode)
                SyncReviewResolutionChoice.SKIP_FOR_NOW -> null
            }
        private suspend fun mergeTextVersion(
            item: SyncReviewItem,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ReviewResolutionOutcome? {
            val mergedText =
                SyncConflictTextMerge.merge(
                    localText = item.localContent,
                    remoteText = item.incomingContent,
                    localLastModified = item.localLastModified,
                    remoteLastModified = item.incomingLastModified,
                ) ?: return null
            val mergedBytes = mergedText.toByteArray(Charsets.UTF_8)
            fileBridgeScope.writeLocalBytes(item.relativePath, mergedBytes, layout)
            val localFile = fileBridgeScope.localFile(item.relativePath, layout)
            val remoteKey =
                objectKeyPolicy.resolveS3OperationKey(
                    relativePath = item.relativePath,
                    remoteFile = remoteFiles[item.relativePath],
                    metadata = metadataByPath[item.relativePath],
                    config = config,
                )
            val uploaded =
                client.putSmallObject(
                    key = remoteKey.value,
                    bytes = encodingSupport.encodeContent(mergedBytes, config),
                    contentType = contentTypeForPath(item.relativePath, layout, runtime, mode),
                    metadata = encodingSupport.objectMetadata(System.currentTimeMillis()),
                )
            return S3ReviewResolutionOutcome(
                path = item.relativePath,
                localFile = localFile,
                remoteFile =
                    RemoteS3File(
                        path = item.relativePath,
                        etag = uploaded.eTag,
                        lastModified = localFile?.lastModified ?: System.currentTimeMillis(),
                        remotePath = remoteKey.value,
                    ),
                direction = com.lomo.domain.model.S3SyncDirection.UPLOAD,
                reason = com.lomo.domain.model.S3SyncReason.LOCAL_NEWER,
            )
        }
        private suspend fun uploadLocalVersion(
            item: SyncReviewItem,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
            mode: S3LocalSyncMode,
        ): S3ReviewResolutionOutcome? {
            return transferWorkspace.withSession { session ->
                val source =
                    fileBridgeScope.exportLocalFile(item.relativePath, layout, session)
                        ?: item.localContent?.let { content ->
                            S3TransferFile(
                                session.createTempFile("s3-review-local-", ".txt").apply {
                                    writeText(content, Charsets.UTF_8)
                                },
                            )
                        }
                        ?: return@withSession null
                val localFile = fileBridgeScope.localFile(item.relativePath, layout)
                val remoteKey =
                    objectKeyPolicy.resolveS3OperationKey(
                        relativePath = item.relativePath,
                        remoteFile = remoteFiles[item.relativePath],
                        metadata = metadataByPath[item.relativePath],
                        config = config,
                    )
                val uploadFile = encodingSupport.prepareUploadFile(source, config, session)
                val uploaded =
                    client.putObjectFile(
                        key = remoteKey.value,
                        file = uploadFile.file,
                        contentType = contentTypeForPath(item.relativePath, layout, runtime, mode),
                        metadata = encodingSupport.objectMetadata(System.currentTimeMillis()),
                    )
                S3ReviewResolutionOutcome(
                    path = item.relativePath,
                    localFile = localFile,
                    remoteFile =
                        RemoteS3File(
                            path = item.relativePath,
                            etag = uploaded.eTag,
                            lastModified = localFile?.lastModified ?: System.currentTimeMillis(),
                            remotePath = remoteKey.value,
                        ),
                    direction = com.lomo.domain.model.S3SyncDirection.UPLOAD,
                    reason = com.lomo.domain.model.S3SyncReason.LOCAL_NEWER,
                )
            }
        }
        private suspend fun downloadIncomingVersion(
            item: SyncReviewItem,
            client: com.lomo.data.s3.LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
            fileBridgeScope: S3SyncFileBridgeScope,
        ): S3ReviewResolutionOutcome {
            val remoteKey =
                objectKeyPolicy.resolveS3OperationKey(
                    relativePath = item.relativePath,
                    remoteFile = remoteFiles[item.relativePath],
                    metadata = metadataByPath[item.relativePath],
                    config = config,
                )
            return transferWorkspace.withSession { session ->
                val downloadedFile = session.createTempFile("s3-review-remote-", ".tmp")
                val remoteObject = client.getObjectToFile(remoteKey.value, downloadedFile)
                val decoded = encodingSupport.decodeDownloadedFile(downloadedFile, config, session)
                fileBridgeScope.importLocalFile(item.relativePath, decoded.file, layout)
                val localFile = fileBridgeScope.localFile(item.relativePath, layout)
                S3ReviewResolutionOutcome(
                    path = item.relativePath,
                    localFile = localFile,
                    remoteFile =
                        RemoteS3File(
                            path = item.relativePath,
                            etag = remoteObject.eTag ?: remoteFiles[item.relativePath]?.etag,
                            lastModified =
                                encodingSupport.resolveRemoteLastModified(
                                    remoteObject.metadata,
                                    remoteObject.lastModified,
                                ) ?: remoteFiles[item.relativePath]?.lastModified,
                            remotePath = remoteKey.value,
                        ),
                    direction = com.lomo.domain.model.S3SyncDirection.DOWNLOAD,
                    reason = com.lomo.domain.model.S3SyncReason.REMOTE_NEWER,
                )
            }
        }
    }
private suspend fun refreshS3ResolutionMemoCache(
    runtime: S3SyncRepositoryContext,
    sessionKind: String,
) {
    runNonFatalCatching {
        runtime.memoSynchronizer.refreshImportedSync()
    }.onFailure { error ->
        Timber.w(error, "Memo refresh after S3 %s resolution failed", sessionKind)
    }
}
private suspend fun commitIncrementalS3ResolutionState(
    protocolStateStore: S3SyncProtocolStateStore,
    localChangeJournalStore: S3LocalChangeJournalStore,
    remoteIndexStore: S3RemoteIndexStore,
    layout: SyncDirectoryLayout,
    mode: S3LocalSyncMode,
    protocolState: S3SyncProtocolState?,
    resolvedRemoteFiles: Map<String, RemoteS3File>,
    resolvedPaths: Set<String>,
) {
    if (resolvedPaths.isEmpty()) return
    if (!protocolStateStore.incrementalSyncEnabled || !localChangeJournalStore.incrementalSyncEnabled) return
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
private data class S3ResolutionContext(
    val protocolState: S3SyncProtocolState?,
    val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
    val remoteFiles: Map<String, RemoteS3File>,
)
private fun PendingSyncConflictDescriptor.toRestoredRemoteFiles(
    config: S3ResolvedConfig,
    objectKeyPolicy: S3RemoteObjectKeyPolicy,
): Map<String, RemoteS3File> =
    files.associate { file ->
        val remoteKey = objectKeyPolicy.validatedExistingKey(file.remote.locator, config)
        file.relativePath to
            RemoteS3File(
                path = file.relativePath,
                etag = file.remote.etag,
                lastModified = file.remote.lastModified,
                size = file.remote.size,
                remotePath = remoteKey.value,
                verificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
            )
    }
private fun PendingSyncReviewDescriptor.toRestoredRemoteFiles(
    config: S3ResolvedConfig,
    objectKeyPolicy: S3RemoteObjectKeyPolicy,
): Map<String, RemoteS3File> =
    items.associate { item ->
        val remoteKey = objectKeyPolicy.validatedExistingKey(item.incoming.locator, config)
        item.relativePath to
            RemoteS3File(
                path = item.relativePath,
                etag = item.incoming.etag,
                lastModified = item.incoming.lastModified,
                size = item.incoming.size,
                remotePath = remoteKey.value,
                verificationLevel = S3RemoteVerificationLevel.VERIFIED_REMOTE,
            )
    }
private fun PendingSyncConflictDescriptor.filterFiles(relativePaths: Set<String>): PendingSyncConflictDescriptor =
    copy(files = files.filter { file -> file.relativePath in relativePaths })
private fun PendingSyncReviewDescriptor.filterItems(relativePaths: Set<String>): PendingSyncReviewDescriptor =
    copy(items = items.filter { item -> item.relativePath in relativePaths })
private fun S3RemoteObjectKeyPolicy.resolveS3OperationKey(
    relativePath: String,
    explicitExistingKey: String? = null,
    remoteFile: RemoteS3File?,
    metadata: com.lomo.data.local.entity.S3SyncMetadataEntity?,
    config: S3ResolvedConfig,
): S3RemoteObjectKey =
    explicitExistingKey?.let { key -> validatedExistingKey(key, config) }
        ?: resolveOperationKey(
            relativePath = relativePath,
            config = config,
            remoteFile = remoteFile,
            metadata = metadata,
        )
private class S3PendingReviewRestorer(
    private val client: com.lomo.data.s3.LomoS3Client,
    private val layout: SyncDirectoryLayout,
    private val config: S3ResolvedConfig,
    private val metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
    private val fileBridgeScope: S3SyncFileBridgeScope,
    private val encodingSupport: S3SyncEncodingSupport,
    private val objectKeyPolicy: S3RemoteObjectKeyPolicy,
) : PendingSyncReviewRestorer {
    override suspend fun restore(
        descriptor: PendingSyncReviewDescriptor,
    ): PendingSyncRestoreResult<SyncReviewSession> {
        val restoredItems = mutableListOf<SyncReviewItem>()
        var invalidation: PendingSyncInvalidationReason? = null
        val iterator = descriptor.items.iterator()
        while (invalidation == null && iterator.hasNext()) {
            when (val restored = restoreItem(iterator.next())) {
                is S3PendingReviewItemRestore.Invalidated -> invalidation = restored.reason
                is S3PendingReviewItemRestore.Restored -> restoredItems += restored.item
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
    private suspend fun restoreItem(item: PendingSyncReviewItemDescriptor): S3PendingReviewItemRestore {
        val local =
            fileBridgeScope.pendingValidationLocalFile(
                path = item.relativePath,
                layout = layout,
                requireContentFingerprint = item.local.contentHash != null,
            ) ?: return S3PendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.MISSING_LOCAL)
        if (!item.local.matchesLocal(local)) {
            return S3PendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
        }
        val remoteKey = remoteKeyFor(item)
        // Binary incoming files have no comparable text, so reject incomplete metadata before any HEAD.
        if (item.isBinary && !item.incoming.hasCompleteRemoteMetadata()) {
            return S3PendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
        }
        val remote =
            client.getObjectMetadata(remoteKey.value)
                ?: return S3PendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.MISSING_REMOTE)
        val remoteLastModified = encodingSupport.resolveRemoteLastModified(remote.metadata, remote.lastModified)
        // Text incoming staleness is validated by content equality in restoreContents; the remote
        // lastModified normalization (cached raw HTTP time vs fresh app-embedded mtime) would otherwise
        // spuriously invalidate an unchanged memo. Binary files keep the etag/mtime/size comparison.
        if (item.isBinary && !item.incoming.matchesRemote(remote.eTag, remoteLastModified, remote.size)) {
            return S3PendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
        }
        return restoreContents(item, local, remoteKey, remoteLastModified)
    }
    private suspend fun restoreContents(
        item: PendingSyncReviewItemDescriptor,
        local: LocalS3File,
        remoteKey: S3RemoteObjectKey,
        remoteLastModified: Long?,
    ): S3PendingReviewItemRestore {
        val localContent = if (item.isBinary) null else fileBridgeScope.readLocalText(item.relativePath, layout)
        val incomingContent =
            if (item.isBinary) {
                null
            } else {
                String(
                    encodingSupport.decodeContent(client.getSmallObject(remoteKey.value).bytes, config),
                    Charsets.UTF_8,
                )
            }
        return when {
            !item.isBinary && !item.local.matchesContent(localContent) ->
                S3PendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_LOCAL)
            !item.isBinary && !item.incoming.matchesContent(incomingContent) ->
                S3PendingReviewItemRestore.Invalidated(PendingSyncInvalidationReason.STALE_REMOTE)
            else ->
                S3PendingReviewItemRestore.Restored(
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
    private fun remoteKeyFor(item: PendingSyncReviewItemDescriptor): S3RemoteObjectKey =
        objectKeyPolicy.validatedExistingKey(item.incoming.locator, config)
}
private sealed interface S3PendingReviewItemRestore {
    data class Restored(
        val item: SyncReviewItem,
    ) : S3PendingReviewItemRestore
    data class Invalidated(
        val reason: PendingSyncInvalidationReason,
    ) : S3PendingReviewItemRestore
}
private class S3ResolutionLifecycleStages(
    private val client: com.lomo.data.s3.LomoS3Client,
    private val workItemCount: Int,
    private val resolve: suspend (com.lomo.data.s3.LomoS3Client) -> S3SyncResult,
    private val mapError: (Throwable) -> S3SyncResult,
) : RemoteSyncLifecycleStages<Unit, Int, Int, Unit, S3SyncResult, S3SyncResult, S3SyncResult, S3SyncResult> {
    override val context: RemoteSyncLifecycleContext =
        RemoteSyncLifecycleContext(
            backend = SyncBackendType.S3,
            budget = RemoteSyncBudgetPolicy.Limited(DEFAULT_REMOTE_SYNC_NETWORK_OPERATION_BUDGET),
        )
    private lateinit var meteredClient: com.lomo.data.s3.LomoS3Client
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
    ): S3SyncResult = resolve(meteredClient)
    override suspend fun commitMetadata(
        verified: Int,
        conflicts: Unit,
        applied: S3SyncResult,
        session: RemoteSyncLifecycleSession,
    ): S3SyncResult = applied
    override suspend fun finalize(
        verified: Int,
        conflicts: Unit,
        applied: S3SyncResult,
        metadata: S3SyncResult,
        session: RemoteSyncLifecycleSession,
    ): S3SyncResult = metadata
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
    override fun summarizeRefresh(finalized: S3SyncResult): RemoteSyncRefreshTelemetry =
        RemoteSyncRefreshTelemetry(durationMillis = 0)
    override fun summarizeResult(finalized: S3SyncResult): RemoteSyncLifecycleResultTelemetry =
        if (finalized is S3SyncResult.Error) {
            RemoteSyncLifecycleResultTelemetry.Failure
        } else {
            RemoteSyncLifecycleResultTelemetry.Success
        }
    override fun mapResult(finalized: S3SyncResult): S3SyncResult = finalized
    override fun mapError(error: Throwable): S3SyncResult = mapError.invoke(error)
    override suspend fun release() = Unit
}
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
    fun hasResolvedActions(): Boolean = actionOutcomes.isNotEmpty()
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
private data class S3AppliedReviewResolution(
    val resolvedLocalFiles: Map<String, LocalS3File>,
    val resolvedRemoteFiles: MutableMap<String, RemoteS3File>,
    val unresolvedItems: List<SyncReviewItem>,
    val actionOutcomes:
        Map<
            String,
            Pair<
                com.lomo.domain.model.S3SyncDirection,
                com.lomo.domain.model.S3SyncReason,
            >,
        >,
) {
    fun hasResolvedActions(): Boolean = actionOutcomes.isNotEmpty()
    fun unresolvedPaths(): Set<String> =
        unresolvedItems.mapTo(linkedSetOf(), SyncReviewItem::relativePath)
}
private data class S3ReviewResolutionOutcome(
    val path: String,
    val localFile: LocalS3File?,
    val remoteFile: RemoteS3File,
    val direction: com.lomo.domain.model.S3SyncDirection,
    val reason: com.lomo.domain.model.S3SyncReason,
)
