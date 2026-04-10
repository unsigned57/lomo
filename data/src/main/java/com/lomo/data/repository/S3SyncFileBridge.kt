package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

@Singleton
class S3SyncFileBridge
    @Inject
    constructor(
        private val runtime: S3SyncRepositoryContext,
        private val encodingSupport: S3SyncEncodingSupport,
        private val safTreeAccess: S3SafTreeAccess,
    ) {
        internal constructor(
            runtime: S3SyncRepositoryContext,
            encodingSupport: S3SyncEncodingSupport,
        ) : this(runtime, encodingSupport, UnsupportedS3SafTreeAccess)

        internal fun modeAware(mode: S3LocalSyncMode): S3SyncFileBridgeScope =
            S3SyncFileBridgeScope(runtime, encodingSupport, safTreeAccess, mode)

        suspend fun localFiles(layout: SyncDirectoryLayout): Map<String, LocalS3File> =
            modeAware(resolveLocalSyncMode(runtime)).localFiles(layout)

        suspend fun remoteFiles(
            client: LomoS3Client,
            layout: SyncDirectoryLayout,
            config: S3ResolvedConfig,
        ): Map<String, RemoteS3File> =
            modeAware(resolveLocalSyncMode(runtime)).remoteFiles(client, layout, config)

        suspend fun persistMetadata(
            localFiles: Map<String, LocalS3File>,
            remoteFiles: Map<String, RemoteS3File>,
            metadataByPath: Map<String, S3SyncMetadataEntity>,
            seededMetadataByPath: Map<String, S3SyncMetadataEntity> = emptyMap(),
            actionOutcomes: Map<String, Pair<S3SyncDirection, S3SyncReason>>,
            syncedContentFingerprints: Map<String, String> = emptyMap(),
            unresolvedPaths: Set<String>,
            completeSnapshot: Boolean = true,
        ) {
            val intersectionPaths = localFiles.keys.intersect(remoteFiles.keys)
            val now = System.currentTimeMillis()
            val upserts =
                (
                    actionOutcomes.keys
                        .asSequence()
                        .filter { path -> path in intersectionPaths }
                        .sorted()
                        .mapNotNull { path ->
                            val local = localFiles[path] ?: return@mapNotNull null
                            val remote = remoteFiles[path] ?: return@mapNotNull null
                            val outcome = requireNotNull(actionOutcomes[path])
                            S3SyncMetadataEntity(
                                relativePath = path,
                                remotePath = remote.remotePath,
                                etag = remote.etag,
                                remoteLastModified = remote.lastModified,
                                localLastModified = local.lastModified,
                                localSize = local.size,
                                remoteSize = remote.size,
                                localFingerprint =
                                    syncedContentFingerprints[path]
                                        ?: normalizeSinglePartS3Md5(remote.etag)
                                        ?: metadataByPath[path]
                                            ?.takeIf { existing ->
                                                existing.localLastModified == local.lastModified &&
                                                    existing.localSize == local.size
                                            }?.localFingerprint,
                                lastSyncedAt = now,
                                lastResolvedDirection = outcome.first.name,
                                lastResolvedReason = outcome.second.name,
                            )
                        }.toList() + seededMetadataByPath.values
                ).associateBy(S3SyncMetadataEntity::relativePath)
                    .values
                    .sortedBy(S3SyncMetadataEntity::relativePath)
            val deletePaths =
                if (completeSnapshot) {
                    metadataByPath.keys
                        .asSequence()
                        .filter { path -> path !in intersectionPaths && path !in unresolvedPaths }
                        .sorted()
                        .toList()
                } else {
                    actionOutcomes.keys
                        .asSequence()
                        .filter { path ->
                            path !in unresolvedPaths &&
                                path !in intersectionPaths &&
                                path in metadataByPath
                        }.sorted()
                        .toList()
                }
            if (deletePaths.isNotEmpty()) {
                runtime.metadataDao.deleteByRelativePaths(deletePaths)
            }
            if (upserts.isNotEmpty()) {
                runtime.metadataDao.upsertAll(upserts)
            }
        }

        suspend fun localFile(
            path: String,
            layout: SyncDirectoryLayout,
        ): LocalS3File? = modeAware(resolveLocalSyncMode(runtime)).localFile(path, layout)

        suspend fun readLocalBytes(
            path: String,
            layout: SyncDirectoryLayout,
        ): ByteArray? = modeAware(resolveLocalSyncMode(runtime)).readLocalBytes(path, layout)

        suspend fun readLocalText(
            path: String,
            layout: SyncDirectoryLayout,
        ): String? = modeAware(resolveLocalSyncMode(runtime)).readLocalText(path, layout)

        suspend fun writeLocalBytes(
            path: String,
            bytes: ByteArray,
            layout: SyncDirectoryLayout,
        ) {
            modeAware(resolveLocalSyncMode(runtime)).writeLocalBytes(path, bytes, layout)
        }

        suspend fun deleteLocalFile(
            path: String,
            layout: SyncDirectoryLayout,
        ) {
            modeAware(resolveLocalSyncMode(runtime)).deleteLocalFile(path, layout)
        }
    }

internal class S3SyncFileBridgeScope(
    private val runtime: S3SyncRepositoryContext,
    private val encodingSupport: S3SyncEncodingSupport,
    private val safTreeAccess: S3SafTreeAccess,
    private val mode: S3LocalSyncMode,
) {
    suspend fun localFiles(layout: SyncDirectoryLayout): Map<String, LocalS3File> =
        when (mode) {
            is S3LocalSyncMode.VaultRoot -> listVaultRootLocalFiles(mode, safTreeAccess)
            is S3LocalSyncMode.Legacy -> legacyLocalFiles(runtime, layout)
        }

    suspend fun remoteFiles(
        client: LomoS3Client,
        layout: SyncDirectoryLayout,
        config: S3ResolvedConfig,
        scanEpoch: Long = 0L,
        onPageListed: suspend (Collection<S3RemoteIndexEntry>) -> Unit = {},
    ): Map<String, RemoteS3File> =
        coroutineScope {
            val limiter = Semaphore(S3_FULL_SCAN_LIST_CONCURRENCY)
            buildRemoteScanPlan(layout, mode)
                .map { shard ->
                    async {
                        limiter.withPermit {
                            listAllRemoteFilesForShard(
                                client = client,
                                layout = layout,
                                config = config,
                                shard = shard,
                                scanEpoch = scanEpoch,
                                onPageListed = onPageListed,
                            )
                        }
                    }
                }.awaitAll()
                .flatten()
                .associate { remoteFile -> remoteFile.path to remoteFile }
        }

    private suspend fun listAllRemoteFilesForShard(
        client: LomoS3Client,
        layout: SyncDirectoryLayout,
        config: S3ResolvedConfig,
        shard: S3RemoteScanShard,
        scanEpoch: Long,
        onPageListed: suspend (Collection<S3RemoteIndexEntry>) -> Unit,
    ): List<RemoteS3File> {
        val remoteFiles = mutableListOf<RemoteS3File>()
        var continuationToken: String? = null
        do {
            val now = System.currentTimeMillis()
            val page =
                client.listPage(
                    prefix = shard.remotePrefix(config, encodingSupport),
                    continuationToken = continuationToken,
                    maxKeys = S3_FULL_SCAN_PAGE_SIZE,
                )
            val pageEntries = mutableListOf<S3RemoteIndexEntry>()
            page.objects.forEach { remote ->
                val decoded =
                    runNonFatalCatching {
                        encodingSupport.decodeRelativePath(remote.key, config)
                    }.getOrNull() ?: return@forEach
                val relativePath = normalizeRemoteRelativePath(decoded, layout, mode) ?: return@forEach
                pageEntries += remote.toRemoteIndexEntry(relativePath, now, scanEpoch)
                remoteFiles +=
                    RemoteS3File(
                        path = relativePath,
                        etag = remote.eTag,
                        lastModified =
                            encodingSupport.resolveRemoteLastModified(remote.metadata, remote.lastModified),
                        size = remote.size,
                        remotePath = remote.key,
                    )
            }
            if (pageEntries.isNotEmpty()) {
                onPageListed(pageEntries)
            }
            continuationToken = page.nextContinuationToken
        } while (continuationToken != null)
        return remoteFiles
    }

    suspend fun localFile(
        path: String,
        layout: SyncDirectoryLayout,
    ): LocalS3File? =
        when (mode) {
            is S3LocalSyncMode.FileVaultRoot ->
                resolveVaultRootPath(path, layout, mode)?.let { relativePath ->
                    getFileVaultRootLocalFile(mode, relativePath)?.copy(path = path)
                }

            is S3LocalSyncMode.SafVaultRoot ->
                resolveVaultRootPath(path, layout, mode)?.let { relativePath ->
                    safTreeAccess.getFile(mode.rootUriString, relativePath)?.let { metadata ->
                        LocalS3File(path = path, lastModified = metadata.lastModified, size = metadata.size)
                    }
                }

            is S3LocalSyncMode.Legacy -> legacyLocalFile(runtime, path, layout, mode)
        }

    suspend fun readLocalBytes(
        path: String,
        layout: SyncDirectoryLayout,
    ): ByteArray? =
        when (mode) {
            is S3LocalSyncMode.VaultRoot ->
                readVaultRootBytes(
                    mode = mode,
                    relativePath = resolveVaultRootPath(path, layout, mode) ?: return null,
                    safTreeAccess = safTreeAccess,
                )

            is S3LocalSyncMode.Legacy -> legacyReadLocalBytes(runtime, path, layout)
        }

    suspend fun readLocalText(
        path: String,
        layout: SyncDirectoryLayout,
    ): String? =
        when (mode) {
            is S3LocalSyncMode.VaultRoot ->
                readVaultRootText(
                    mode = mode,
                    relativePath = resolveVaultRootPath(path, layout, mode) ?: return null,
                    safTreeAccess = safTreeAccess,
                )

            is S3LocalSyncMode.Legacy ->
                if (legacyIsMemoPath(path, layout)) {
                    runtime.markdownStorageDataSource.readFileIn(
                        MemoDirectoryType.MAIN,
                        legacyExtractMemoFilename(path, layout),
                    )
                } else {
                    null
                }
        }

    suspend fun exportLocalFile(
        path: String,
        layout: SyncDirectoryLayout,
        session: S3SyncTransferSession,
    ): S3TransferFile? =
        when (mode) {
            is S3LocalSyncMode.VaultRoot ->
                exportVaultRootFile(
                    mode = mode,
                    relativePath = resolveVaultRootPath(path, layout, mode) ?: return null,
                    safTreeAccess = safTreeAccess,
                    session = session,
                )

            is S3LocalSyncMode.Legacy ->
                legacyExportLocalFile(
                    runtime = runtime,
                    path = path,
                    layout = layout,
                    mode = mode,
                    session = session,
                )
        }

    suspend fun writeLocalBytes(
        path: String,
        bytes: ByteArray,
        layout: SyncDirectoryLayout,
    ) {
        when (mode) {
            is S3LocalSyncMode.VaultRoot ->
                writeVaultRootBytes(
                    mode = mode,
                    relativePath = resolveVaultRootPath(path, layout, mode) ?: return,
                    bytes = bytes,
                    safTreeAccess = safTreeAccess,
                )

            is S3LocalSyncMode.Legacy -> legacyWriteLocalBytes(runtime, path, bytes, layout)
        }
    }

    suspend fun importLocalFile(
        path: String,
        source: File,
        layout: SyncDirectoryLayout,
    ) {
        when (mode) {
            is S3LocalSyncMode.VaultRoot ->
                importVaultRootFile(
                    mode = mode,
                    relativePath = resolveVaultRootPath(path, layout, mode) ?: return,
                    source = source,
                    safTreeAccess = safTreeAccess,
                )

            is S3LocalSyncMode.Legacy ->
                legacyImportLocalFile(
                    runtime = runtime,
                    path = path,
                    source = source,
                    layout = layout,
                    mode = mode,
                )
        }
    }

    suspend fun deleteLocalFile(
        path: String,
        layout: SyncDirectoryLayout,
    ) {
        when (mode) {
            is S3LocalSyncMode.VaultRoot ->
                deleteVaultRootFile(
                    mode = mode,
                    relativePath = resolveVaultRootPath(path, layout, mode) ?: return,
                    safTreeAccess = safTreeAccess,
                )

            is S3LocalSyncMode.Legacy -> legacyDeleteLocalFile(runtime, path, layout)
        }
    }
}

private const val S3_FULL_SCAN_LIST_CONCURRENCY = 3
private const val S3_FULL_SCAN_PAGE_SIZE = 500
