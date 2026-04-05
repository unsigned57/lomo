package com.lomo.data.repository

import androidx.room.withTransaction
import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.dao.MemoFtsDao
import com.lomo.data.local.dao.MemoTagDao
import com.lomo.data.local.dao.MemoTrashDao
import com.lomo.data.local.dao.MemoWriteDao
import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoFtsEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.util.MemoTextProcessor
import com.lomo.data.util.SearchTokenizer
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import com.lomo.domain.repository.MemoVersionRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

data class MemoVersionCommitRecord(
    val commitId: String,
    val createdAt: Long,
    val origin: MemoRevisionOrigin,
    val actor: String,
    val batchId: String?,
    val summary: String,
)

data class MemoVersionBlobRecord(
    val blobHash: String,
    val storagePath: String,
    val byteSize: Long,
    val contentEncoding: String,
    val createdAt: Long,
)

data class MemoVersionRevisionRecord(
    val revisionId: String,
    val memoId: String,
    val parentRevisionId: String?,
    val commitId: String,
    val dateKey: String,
    val lifecycleState: MemoRevisionLifecycleState,
    val rawMarkdownBlobHash: String,
    val contentHash: String,
    val assetFingerprint: String?,
    val memoTimestamp: Long,
    val memoUpdatedAt: Long,
    val memoContent: String,
    val createdAt: Long,
)

data class MemoVersionRevisionHistoryRecord(
    val revisionId: String,
    val parentRevisionId: String?,
    val memoId: String,
    val commitId: String,
    val batchId: String?,
    val origin: MemoRevisionOrigin,
    val summary: String,
    val lifecycleState: MemoRevisionLifecycleState,
    val memoContent: String,
    val contentHash: String,
    val createdAt: Long,
)

data class MemoVersionAssetRecord(
    val revisionId: String,
    val logicalPath: String,
    val blobHash: String,
    val contentEncoding: String,
)

data class MemoSnapshotRetentionSettings(
    val enabled: Boolean,
    val maxCount: Int,
    val maxAgeDays: Int,
)

sealed interface ImportedMemoRevisionChange {
    data class Upsert(
        val memo: Memo,
        val lifecycleState: MemoRevisionLifecycleState,
    ) : ImportedMemoRevisionChange

    data class Delete(
        val memoId: String,
        val dateKey: String,
        val rawContent: String,
        val content: String,
        val timestamp: Long,
        val updatedAt: Long,
    ) : ImportedMemoRevisionChange
}

interface MemoVersionStoreWriter {
    suspend fun insertCommit(record: MemoVersionCommitRecord)

    suspend fun insertRevision(record: MemoVersionRevisionRecord)

    suspend fun replaceAssets(
        revisionId: String,
        records: List<MemoVersionAssetRecord>,
    )

    suspend fun insertBlob(record: MemoVersionBlobRecord)

    suspend fun deleteAssetsByRevisionIds(revisionIds: List<String>)

    suspend fun deleteRevisionsByIds(revisionIds: List<String>)

    suspend fun deleteBlob(blobHash: String)

    suspend fun clearAll()
}

interface MemoVersionStoreReader {
    suspend fun getBlob(blobHash: String): MemoVersionBlobRecord?

    suspend fun getRevision(revisionId: String): MemoVersionRevisionRecord?

    suspend fun getCommit(commitId: String): MemoVersionCommitRecord?

    suspend fun listRevisionHistoryForMemo(
        memoId: String,
        cursor: MemoRevisionCursor?,
        limit: Int,
    ): List<MemoVersionRevisionHistoryRecord>

    suspend fun getLatestRevisionForMemo(memoId: String): MemoVersionRevisionRecord?

    suspend fun findEquivalentRevisionsForMemo(
        memoId: String,
        lifecycleState: MemoRevisionLifecycleState,
        rawMarkdownBlobHash: String,
        contentHash: String,
        assetFingerprint: String,
    ): List<MemoVersionRevisionRecord>

    suspend fun listAssetsForRevision(revisionId: String): List<MemoVersionAssetRecord>

    suspend fun listStaleRevisionsForMemo(
        memoId: String,
        retainCount: Int,
        olderThanCreatedAt: Long?,
    ): List<MemoVersionRevisionRecord>

    suspend fun listAssetsForRevisionIds(revisionIds: List<String>): List<MemoVersionAssetRecord>

    suspend fun isBlobReferenced(blobHash: String): Boolean

    suspend fun listAllRevisionsForMemo(memoId: String): List<MemoVersionRevisionRecord>
}

interface MemoVersionStore : MemoVersionStoreWriter, MemoVersionStoreReader

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MemoVersionBlobRoot

@Singleton
class MemoVersionJournal
    constructor(
        private val store: MemoVersionStore,
        @MemoVersionBlobRoot private val blobRoot: File,
        private val markdownStorageDataSource: MarkdownStorageDataSource,
        private val workspaceMediaAccess: WorkspaceMediaAccess,
        private val memoTextProcessor: MemoTextProcessor,
        private val memoWriteDao: MemoWriteDao? = null,
        private val memoTagDao: MemoTagDao? = null,
        private val memoFtsDao: MemoFtsDao? = null,
        private val memoTrashDao: MemoTrashDao? = null,
        private val mediaRepository: MediaRepository? = null,
        private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
        private val now: () -> Long = { System.currentTimeMillis() },
        private val nextCommitId: () -> String = { UUID.randomUUID().toString() },
        private val nextRevisionId: () -> String = { UUID.randomUUID().toString() },
        private val nextBatchId: () -> String = { UUID.randomUUID().toString() },
        private val maxRevisionsPerMemo: Int = DEFAULT_MAX_REVISIONS_PER_MEMO,
        private val loadSnapshotSettings: suspend () -> MemoSnapshotRetentionSettings = {
            MemoSnapshotRetentionSettings(
                enabled = true,
                maxCount = maxRevisionsPerMemo,
                maxAgeDays = Int.MAX_VALUE,
            )
        },
    ) : MemoVersionRepository {
        @Inject
        constructor(
            store: MemoVersionStore,
            @MemoVersionBlobRoot blobRoot: File,
            markdownStorageDataSource: MarkdownStorageDataSource,
            workspaceMediaAccess: WorkspaceMediaAccess,
            memoTextProcessor: MemoTextProcessor,
            memoWriteDao: MemoWriteDao,
            memoTagDao: MemoTagDao,
            memoFtsDao: MemoFtsDao,
            memoTrashDao: MemoTrashDao,
            mediaRepository: MediaRepository,
            database: MemoDatabase,
            memoSnapshotPreferencesRepository: MemoSnapshotPreferencesRepository,
        ) : this(
            store = store,
            blobRoot = blobRoot,
            markdownStorageDataSource = markdownStorageDataSource,
            workspaceMediaAccess = workspaceMediaAccess,
            memoTextProcessor = memoTextProcessor,
            memoWriteDao = memoWriteDao,
            memoTagDao = memoTagDao,
            memoFtsDao = memoFtsDao,
            memoTrashDao = memoTrashDao,
            mediaRepository = mediaRepository,
            runInTransaction = { block ->
                database.withTransaction {
                    block()
                }
            },
            loadSnapshotSettings = {
                MemoSnapshotRetentionSettings(
                    enabled = memoSnapshotPreferencesRepository.isMemoSnapshotsEnabled().first(),
                    maxCount = memoSnapshotPreferencesRepository.getMemoSnapshotMaxCount().first(),
                    maxAgeDays = memoSnapshotPreferencesRepository.getMemoSnapshotMaxAgeDays().first(),
                )
            },
        )

        suspend fun appendLocalRevision(
            memo: Memo,
            lifecycleState: MemoRevisionLifecycleState,
            origin: MemoRevisionOrigin,
        ) {
            appendRevision(
                memoState = MemoVersionMemoState.fromMemo(memo),
                lifecycleState = lifecycleState,
                origin = origin,
                sharedCommit = null,
            )
        }

        suspend fun appendImportedRefreshRevisions(
            changes: List<ImportedMemoRevisionChange>,
            origin: MemoRevisionOrigin = MemoRevisionOrigin.IMPORT_REFRESH,
        ) {
            if (changes.isEmpty()) {
                return
            }

            var sharedCommit: MemoVersionCommitRecord? = null
            val batchId = nextBatchId()
            changes.forEach { change ->
                val memoState =
                    when (change) {
                        is ImportedMemoRevisionChange.Upsert -> MemoVersionMemoState.fromMemo(change.memo)
                        is ImportedMemoRevisionChange.Delete ->
                            MemoVersionMemoState(
                                memoId = change.memoId,
                                dateKey = change.dateKey,
                                timestamp = change.timestamp,
                                updatedAt = change.updatedAt,
                                content = change.content,
                                rawContent = change.rawContent,
                            )
                    }
                val lifecycleState =
                    when (change) {
                        is ImportedMemoRevisionChange.Upsert -> change.lifecycleState
                        is ImportedMemoRevisionChange.Delete -> MemoRevisionLifecycleState.DELETED
                    }
                if (sharedCommit == null) {
                    sharedCommit =
                        MemoVersionCommitRecord(
                            commitId = nextCommitId(),
                            createdAt = now(),
                            origin = origin,
                            actor = ACTOR_IMPORT,
                            batchId = batchId,
                            summary = summaryFor(origin = origin, lifecycleState = lifecycleState),
                        )
                }
                appendRevision(
                    memoState = memoState,
                    lifecycleState = lifecycleState,
                    origin = origin,
                    sharedCommit = sharedCommit,
                )
            }
        }

        override suspend fun listMemoRevisions(
            memo: Memo,
            cursor: MemoRevisionCursor?,
            limit: Int,
        ): MemoRevisionPage {
            if (limit <= 0) {
                return MemoRevisionPage(items = emptyList(), nextCursor = null)
            }
            val currentLifecycleState = memo.currentLifecycleState()
            val currentContentHash = memo.rawContent.toVersionHash()
            val rows =
                store.listRevisionHistoryForMemo(
                    memoId = memo.id,
                    cursor = cursor,
                    limit = limit + 1,
                )
            val visibleRows = rows.take(limit)
            val nextCursor =
                if (rows.size > limit) {
                    visibleRows.lastOrNull()?.let { row ->
                        MemoRevisionCursor(
                            createdAt = row.createdAt,
                            revisionId = row.revisionId,
                        )
                    }
                } else {
                    null
                }
            return MemoRevisionPage(
                items =
                    visibleRows.map { revision ->
                        MemoRevision(
                            revisionId = revision.revisionId,
                            parentRevisionId = revision.parentRevisionId,
                            memoId = revision.memoId,
                            commitId = revision.commitId,
                            batchId = revision.batchId,
                            createdAt = revision.createdAt,
                            origin = revision.origin,
                            summary = revision.summary,
                            lifecycleState = revision.lifecycleState,
                            memoContent = revision.memoContent,
                            isCurrent =
                                revision.lifecycleState == currentLifecycleState &&
                                    revision.contentHash == currentContentHash,
                        )
                    },
                nextCursor = nextCursor,
            )
        }

        override suspend fun restoreMemoRevision(
            currentMemo: Memo,
            revisionId: String,
        ) {
            val revision = requireNotNull(store.getRevision(revisionId)) { "Revision not found: $revisionId" }
            val filename = "${revision.dateKey}.md"
            val rawContent =
                readMemoVersionBlobContent(
                    store = store,
                    blobRoot = blobRoot,
                    blobHash = revision.rawMarkdownBlobHash,
                )
            val snapshot =
                captureRestoreSnapshot(
                    markdownStorageDataSource = markdownStorageDataSource,
                    workspaceMediaAccess = workspaceMediaAccess,
                    store = store,
                    revisionId = revisionId,
                    filename = filename,
                )
            val restoreFailure =
                runCatching {
                    restoreRevisionAssets(
                        revisionId = revisionId,
                        store = store,
                        blobRoot = blobRoot,
                        workspaceMediaAccess = workspaceMediaAccess,
                    )
                    when (revision.lifecycleState) {
                        MemoRevisionLifecycleState.ACTIVE -> restoreActiveRevision(currentMemo, revision, rawContent)
                        MemoRevisionLifecycleState.TRASHED -> restoreTrashedRevision(currentMemo, revision, rawContent)
                        MemoRevisionLifecycleState.DELETED -> restoreDeletedRevision(currentMemo, revision)
                    }
                    mediaRepository?.refreshImageLocations()
                    appendRestoredRevision(
                        restoredRevision = revision,
                        rawContent = rawContent,
                        store = store,
                        memoTextProcessor = memoTextProcessor,
                        runInTransaction = runInTransaction,
                        loadSnapshotSettings = loadSnapshotSettings,
                        now = now,
                        nextCommitId = nextCommitId,
                        nextRevisionId = nextRevisionId,
                        pruneRevisionsForMemo = ::pruneRevisionsForMemo,
                    )
                }.exceptionOrNull()
            if (restoreFailure != null) {
                rollbackRestoreSnapshot(
                    snapshot = snapshot,
                    markdownStorageDataSource = markdownStorageDataSource,
                    workspaceMediaAccess = workspaceMediaAccess,
                )
                rollbackCurrentMemoState(
                    currentMemo = currentMemo,
                    persistActiveMemo = { memo -> persistActiveMemo(memo) },
                    persistTrashedMemo = { memo -> persistTrashedMemo(memo) },
                )
                mediaRepository?.refreshImageLocations()
                throw restoreFailure
            }
        }

        override suspend fun clearAllMemoSnapshots() {
            runInTransaction {
                store.clearAll()
            }
            clearBlobRootDirectory()
        }

        private suspend fun appendRevision(
            memoState: MemoVersionMemoState,
            lifecycleState: MemoRevisionLifecycleState,
            origin: MemoRevisionOrigin,
            sharedCommit: MemoVersionCommitRecord?,
        ) {
            val snapshotSettings = loadSnapshotSettings()
            if (!snapshotSettings.enabled) {
                return
            }
            val appendPayload = buildAppendRevisionPayload(memoState)
            val newlyPersistedBlobHashes = linkedSetOf<String>()
            val appendFailure =
                runCatching {
                    runInTransaction {
                        val latestRevision = store.getLatestRevisionForMemo(memoState.memoId)
                        val createdAt = nextMemoVersionCreatedAt(latestRevision = latestRevision, now = now)
                        val latestAssetPairs =
                            loadLatestAssetPairsIfNeeded(
                                store = store,
                                latestRevision = latestRevision,
                            )
                        if (
                            latestRevision.matchesCurrentState(
                                rawContentHash = appendPayload.rawContentHash,
                                lifecycleState = lifecycleState,
                                assetFingerprint = appendPayload.assetFingerprint,
                                assetPairs = appendPayload.assetPairs,
                                latestAssetPairs = latestAssetPairs,
                            )
                        ) {
                            return@runInTransaction
                        }
                        if (
                            store.hasEquivalentHistoricalRevision(
                                memoId = memoState.memoId,
                                lifecycleState = lifecycleState,
                                rawMarkdownBlobHash = appendPayload.rawMarkdownBlobHash,
                                contentHash = appendPayload.rawContentHash,
                                assetFingerprint = appendPayload.assetFingerprint,
                                assetPairs = appendPayload.assetPairs,
                            )
                        ) {
                            return@runInTransaction
                        }
                        trackAndPersistMemoVersionBlobIfNeeded(
                            store = store,
                            blobRoot = blobRoot,
                            bytes = appendPayload.rawBytes,
                            contentEncoding = VERSION_MARKDOWN_CONTENT_ENCODING,
                            createdAt = createdAt,
                            newlyPersistedBlobHashes = newlyPersistedBlobHashes,
                        )
                        val persistedAssets =
                            persistResolvedRevisionAssets(
                                store = store,
                                blobRoot = blobRoot,
                                createdAt = createdAt,
                                assets = appendPayload.assets,
                                newlyPersistedBlobHashes = newlyPersistedBlobHashes,
                            )
                        insertMemoVersionRevision(
                            store = store,
                            memoState = memoState,
                            lifecycleState = lifecycleState,
                            origin = origin,
                            sharedCommit = sharedCommit,
                            latestRevisionId = latestRevision?.revisionId,
                            rawMarkdownBlobHash = appendPayload.rawMarkdownBlobHash,
                            rawContentHash = appendPayload.rawContentHash,
                            assetFingerprint = appendPayload.assetFingerprint,
                            createdAt = createdAt,
                            nextCommitId = nextCommitId,
                            nextRevisionId = nextRevisionId,
                            persistedAssets = persistedAssets,
                        )
                        pruneRevisionsForMemo(
                            memoId = memoState.memoId,
                            snapshotSettings = snapshotSettings,
                            referenceTimeMillis = createdAt,
                        )
                    }
                }.exceptionOrNull()
            if (appendFailure != null) {
                cleanupMemoVersionBlobWriteFailures(
                    store = store,
                    blobRoot = blobRoot,
                    blobHashes = newlyPersistedBlobHashes,
                )
                throw appendFailure
            }
        }

        private suspend fun buildAppendRevisionPayload(memoState: MemoVersionMemoState): MemoVersionAppendPayload {
            val rawBytes = memoState.rawContent.toByteArray(StandardCharsets.UTF_8)
            val assets =
                captureRevisionAssets(
                    memoState = memoState,
                    memoTextProcessor = memoTextProcessor,
                    workspaceMediaAccess = workspaceMediaAccess,
                )
            val assetPairs = assets.map(ResolvedMemoRevisionAsset::pair)
            val rawContentHash = rawBytes.toVersionHash()
            return MemoVersionAppendPayload(
                rawBytes = rawBytes,
                rawContentHash = rawContentHash,
                rawMarkdownBlobHash = rawContentHash,
                assets = assets,
                assetPairs = assetPairs,
                assetFingerprint = assetPairs.toMemoVersionAssetFingerprint(),
            )
        }

        private suspend fun restoreActiveRevision(
            currentMemo: Memo,
            revision: MemoVersionRevisionRecord,
            rawContent: String,
        ) {
            val restoredMemo = revision.toMemo(rawContent, memoTextProcessor).copy(isDeleted = false)
            val filename = "${revision.dateKey}.md"
            rewriteMemoIntoDirectory(
                markdownStorageDataSource = markdownStorageDataSource,
                directory = MemoDirectoryType.MAIN,
                filename = filename,
                currentMemo = currentMemo.copy(isDeleted = false),
                replacementRawContent = rawContent,
                memoTextProcessor = memoTextProcessor,
            )
            removeMemoFromDirectoryIfPresent(
                markdownStorageDataSource = markdownStorageDataSource,
                directory = MemoDirectoryType.TRASH,
                filename = filename,
                memo = currentMemo.copy(isDeleted = true),
                memoTextProcessor = memoTextProcessor,
            )
            persistActiveMemo(restoredMemo)
        }

        private suspend fun restoreTrashedRevision(
            currentMemo: Memo,
            revision: MemoVersionRevisionRecord,
            rawContent: String,
        ) {
            val trashedMemo = revision.toMemo(rawContent, memoTextProcessor).copy(isDeleted = true)
            val filename = "${revision.dateKey}.md"
            rewriteMemoIntoDirectory(
                markdownStorageDataSource = markdownStorageDataSource,
                directory = MemoDirectoryType.TRASH,
                filename = filename,
                currentMemo = currentMemo.copy(isDeleted = true),
                replacementRawContent = rawContent,
                memoTextProcessor = memoTextProcessor,
            )
            removeMemoFromDirectoryIfPresent(
                markdownStorageDataSource = markdownStorageDataSource,
                directory = MemoDirectoryType.MAIN,
                filename = filename,
                memo = currentMemo.copy(isDeleted = false),
                memoTextProcessor = memoTextProcessor,
            )
            persistTrashedMemo(trashedMemo)
        }

        private suspend fun restoreDeletedRevision(
            currentMemo: Memo,
            revision: MemoVersionRevisionRecord,
        ) {
            val filename = "${revision.dateKey}.md"
            removeMemoFromDirectoryIfPresent(
                markdownStorageDataSource = markdownStorageDataSource,
                directory = MemoDirectoryType.MAIN,
                filename = filename,
                memo = currentMemo.copy(isDeleted = false),
                memoTextProcessor = memoTextProcessor,
            )
            removeMemoFromDirectoryIfPresent(
                markdownStorageDataSource = markdownStorageDataSource,
                directory = MemoDirectoryType.TRASH,
                filename = filename,
                memo = currentMemo.copy(isDeleted = true),
                memoTextProcessor = memoTextProcessor,
            )
            if (memoWriteDao == null || memoTagDao == null || memoFtsDao == null || memoTrashDao == null) {
                return
            }
            runInTransaction {
                memoWriteDao.deleteMemoById(revision.memoId)
                memoTagDao.deleteTagRefsByMemoId(revision.memoId)
                memoFtsDao.deleteMemoFts(revision.memoId)
                memoTrashDao.deleteTrashMemoById(revision.memoId)
            }
        }

        private suspend fun persistActiveMemo(memo: Memo) {
            if (memoWriteDao == null || memoTagDao == null || memoFtsDao == null || memoTrashDao == null) {
                return
            }
            runInTransaction {
                val entity = MemoEntity.fromDomain(memo.copy(isDeleted = false))
                memoWriteDao.insertMemo(entity)
                memoTagDao.replaceTagRefsForMemo(entity)
                memoFtsDao.insertMemoFts(
                    MemoFtsEntity(
                        memoId = entity.id,
                        content = SearchTokenizer.tokenize(entity.content),
                    ),
                )
                memoTrashDao.deleteTrashMemoById(entity.id)
            }
        }

        private suspend fun persistTrashedMemo(memo: Memo) {
            if (memoWriteDao == null || memoTagDao == null || memoFtsDao == null || memoTrashDao == null) {
                return
            }
            runInTransaction {
                memoWriteDao.deleteMemoById(memo.id)
                memoTagDao.deleteTagRefsByMemoId(memo.id)
                memoFtsDao.deleteMemoFts(memo.id)
                memoTrashDao.insertTrashMemo(TrashMemoEntity.fromDomain(memo.copy(isDeleted = true)))
            }
        }

        private suspend fun pruneRevisionsForMemo(
            memoId: String,
            snapshotSettings: MemoSnapshotRetentionSettings,
            referenceTimeMillis: Long,
        ) {
            if (snapshotSettings.maxCount <= 0 && snapshotSettings.maxAgeDays <= 0) {
                return
            }
            val ageCutoff =
                if (snapshotSettings.maxAgeDays in 1 until Int.MAX_VALUE) {
                    referenceTimeMillis - snapshotSettings.maxAgeDays.toLong() * MILLIS_PER_DAY
                } else {
                    Long.MIN_VALUE
                }
            val staleRevisions =
                store.listStaleRevisionsForMemo(
                    memoId = memoId,
                    retainCount = snapshotSettings.maxCount.coerceAtLeast(0),
                    olderThanCreatedAt =
                        ageCutoff.takeIf {
                            snapshotSettings.maxAgeDays in 1 until Int.MAX_VALUE
                        },
                )
            if (staleRevisions.isEmpty()) {
                return
            }
            val staleRevisionIds = staleRevisions.map(MemoVersionRevisionRecord::revisionId)
            val staleAssets = store.listAssetsForRevisionIds(staleRevisionIds)
            val candidateBlobHashes =
                buildSet {
                    addAll(staleRevisions.map(MemoVersionRevisionRecord::rawMarkdownBlobHash))
                    addAll(staleAssets.map(MemoVersionAssetRecord::blobHash))
                }
            store.deleteAssetsByRevisionIds(staleRevisionIds)
            store.deleteRevisionsByIds(staleRevisionIds)
            candidateBlobHashes.forEach { blobHash ->
                deleteMemoVersionBlobIfUnreferenced(
                    store = store,
                    blobRoot = blobRoot,
                    blobHash = blobHash,
                )
            }
        }

        private fun clearBlobRootDirectory() {
            blobRoot.mkdirs()
            blobRoot.listFiles()?.forEach { child ->
                if (!child.deleteRecursively()) {
                    throw java.io.IOException("Failed to clear memo snapshot blob: ${child.absolutePath}")
                }
            }
        }

    }

internal data class MemoVersionMemoState(
    val memoId: String,
    val dateKey: String,
    val timestamp: Long,
    val updatedAt: Long,
    val content: String,
    val rawContent: String,
) {
    companion object {
        fun fromMemo(memo: Memo): MemoVersionMemoState =
            MemoVersionMemoState(
                memoId = memo.id,
                dateKey = memo.dateKey,
                timestamp = memo.timestamp,
                updatedAt = memo.updatedAt,
                content = memo.content,
                rawContent = memo.rawContent,
            )
    }
}

internal data class MemoVersionAppendPayload(
    val rawBytes: ByteArray,
    val rawContentHash: String,
    val rawMarkdownBlobHash: String,
    val assets: List<ResolvedMemoRevisionAsset>,
    val assetPairs: List<Pair<String, String>>,
    val assetFingerprint: String,
)

internal suspend fun loadLatestAssetPairsIfNeeded(
    store: MemoVersionStore,
    latestRevision: MemoVersionRevisionRecord?,
): List<Pair<String, String>> =
    if (latestRevision?.assetFingerprint == null) {
        listRevisionAssetPairs(store = store, revision = latestRevision)
    } else {
        emptyList()
    }

internal data class ResolvedMemoRevisionAttachment(
    val logicalPath: String,
    val contentEncoding: String,
    val bytes: ByteArray,
)

internal const val ACTOR_IMPORT = "import"
internal const val ACTOR_LOCAL = "local"
internal const val LOGICAL_IMAGE_PREFIX = "images/"
internal const val LOGICAL_VOICE_PREFIX = "voice/"
internal const val UNASSIGNED_REVISION_ID = "__pending__"
internal const val VERSION_MARKDOWN_CONTENT_ENCODING = "text/markdown;charset=utf-8"
internal const val DEFAULT_MAX_REVISIONS_PER_MEMO = 100
internal const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
internal val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "ogg", "wav")
internal val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")
