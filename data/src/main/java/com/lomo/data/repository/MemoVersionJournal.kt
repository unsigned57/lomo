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
import com.lomo.domain.repository.MemoVersionRepository
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
    ): List<MemoVersionRevisionRecord>

    suspend fun listAssetsForRevision(revisionId: String): List<MemoVersionAssetRecord>

    suspend fun listStaleRevisionsForMemo(
        memoId: String,
        retainCount: Int,
    ): List<MemoVersionRevisionRecord>

    suspend fun listAssetsForRevisionIds(revisionIds: List<String>): List<MemoVersionAssetRecord>

    suspend fun isBlobReferenced(blobHash: String): Boolean
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
            val rawContent =
                readMemoVersionBlobContent(
                    store = store,
                    blobRoot = blobRoot,
                    blobHash = revision.rawMarkdownBlobHash,
                )
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
            appendLocalRevision(
                memo = revision.toMemo(rawContent, memoTextProcessor),
                lifecycleState = revision.lifecycleState,
                origin = MemoRevisionOrigin.LOCAL_RESTORE,
            )
        }

        private suspend fun appendRevision(
            memoState: MemoVersionMemoState,
            lifecycleState: MemoRevisionLifecycleState,
            origin: MemoRevisionOrigin,
            sharedCommit: MemoVersionCommitRecord?,
        ) {
            val latestRevision = store.getLatestRevisionForMemo(memoState.memoId)
            val createdAt =
                latestRevision
                    ?.createdAt
                    ?.let { latestCreatedAt ->
                        maxOf(now(), latestCreatedAt + 1)
                    } ?: now()
            val rawBytes = memoState.rawContent.toByteArray(StandardCharsets.UTF_8)
            val rawContentHash = rawBytes.toVersionHash()
            val rawMarkdownBlobHash = rawContentHash
            val assets =
                captureRevisionAssets(
                    memoState = memoState,
                    memoTextProcessor = memoTextProcessor,
                    workspaceMediaAccess = workspaceMediaAccess,
                )
            val assetPairs = assets.map(ResolvedMemoRevisionAsset::pair)
            val latestAssetPairs =
                latestRevision
                    ?.let { revision ->
                        store
                            .listAssetsForRevision(revision.revisionId)
                            .map(MemoVersionAssetRecord::pair)
                    }
                    .orEmpty()
            if (latestRevision.matchesCurrentState(rawContentHash, lifecycleState, assetPairs, latestAssetPairs)) {
                return
            }
            if (
                hasEquivalentHistoricalRevision(
                    memoId = memoState.memoId,
                    lifecycleState = lifecycleState,
                    rawMarkdownBlobHash = rawMarkdownBlobHash,
                    contentHash = rawContentHash,
                    assetPairs = assetPairs,
                )
            ) {
                return
            }
            persistMemoVersionBlobIfNeeded(
                store = store,
                blobRoot = blobRoot,
                bytes = rawBytes,
                contentEncoding = VERSION_MARKDOWN_CONTENT_ENCODING,
                createdAt = createdAt,
            )
            val persistedAssets =
                persistResolvedRevisionAssets(
                    store = store,
                    blobRoot = blobRoot,
                    createdAt = createdAt,
                    assets = assets,
                )

            val commit =
                sharedCommit
                    ?: MemoVersionCommitRecord(
                        commitId = nextCommitId(),
                        createdAt = createdAt,
                        origin = origin,
                        actor = ACTOR_LOCAL,
                        batchId = null,
                        summary = summaryFor(origin = origin, lifecycleState = lifecycleState),
                    )
            store.insertCommit(commit)
            val revisionId = nextRevisionId()
            store.insertRevision(
                MemoVersionRevisionRecord(
                    revisionId = revisionId,
                    memoId = memoState.memoId,
                    parentRevisionId = latestRevision?.revisionId,
                    commitId = commit.commitId,
                    dateKey = memoState.dateKey,
                    lifecycleState = lifecycleState,
                    rawMarkdownBlobHash = rawMarkdownBlobHash,
                    contentHash = rawContentHash,
                    memoTimestamp = memoState.timestamp,
                    memoUpdatedAt = memoState.updatedAt,
                    memoContent = memoState.content,
                    createdAt = createdAt,
                ),
            )
            store.replaceAssets(
                revisionId = revisionId,
                records = persistedAssets.map { asset -> asset.copy(revisionId = revisionId) },
            )
            pruneRevisionsForMemo(memoState.memoId)
        }

        private fun MemoVersionRevisionRecord?.matchesCurrentState(
            rawContentHash: String,
            lifecycleState: MemoRevisionLifecycleState,
            assetPairs: List<Pair<String, String>>,
            latestAssetPairs: List<Pair<String, String>>,
        ): Boolean =
            this != null &&
                contentHash == rawContentHash &&
                this.lifecycleState == lifecycleState &&
                latestAssetPairs == assetPairs

        private suspend fun hasEquivalentHistoricalRevision(
            memoId: String,
            lifecycleState: MemoRevisionLifecycleState,
            rawMarkdownBlobHash: String,
            contentHash: String,
            assetPairs: List<Pair<String, String>>,
        ): Boolean =
            store
                .findEquivalentRevisionsForMemo(
                    memoId = memoId,
                    lifecycleState = lifecycleState,
                    rawMarkdownBlobHash = rawMarkdownBlobHash,
                    contentHash = contentHash,
                ).any { revision ->
                    store.listAssetsForRevision(revision.revisionId).map(MemoVersionAssetRecord::pair) == assetPairs
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

        private suspend fun pruneRevisionsForMemo(memoId: String) {
            if (maxRevisionsPerMemo <= 0) {
                return
            }
            runInTransaction {
                val staleRevisions =
                    store.listStaleRevisionsForMemo(
                        memoId = memoId,
                        retainCount = maxRevisionsPerMemo,
                    )
                if (staleRevisions.isEmpty()) {
                    return@runInTransaction
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
internal val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "ogg", "wav")
internal val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")
