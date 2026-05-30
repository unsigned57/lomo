package com.lomo.data.repository

import com.lomo.data.local.MemoDatabase
import com.lomo.data.local.withDriverTransaction
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import com.lomo.domain.model.MediaFileExtensions
import com.lomo.domain.repository.MemoSnapshotPreferencesRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.OutputStream
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
        internal val store: MemoVersionStore,
        @MemoVersionBlobRoot internal val blobRoot: File,
        private val workspaceMediaAccess: WorkspaceMediaAccess,
        internal val memoTextProcessor: MemoTextProcessor,
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
    ) {
        @Inject
        constructor(
            store: MemoVersionStore,
            @MemoVersionBlobRoot blobRoot: File,
            workspaceMediaAccess: WorkspaceMediaAccess,
            memoTextProcessor: MemoTextProcessor,
            database: MemoDatabase,
            memoSnapshotPreferencesRepository: MemoSnapshotPreferencesRepository,
        ) : this(
            store = store,
            blobRoot = blobRoot,
            workspaceMediaAccess = workspaceMediaAccess,
            memoTextProcessor = memoTextProcessor,
            runInTransaction = { block ->
                database.withDriverTransaction {
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

        suspend fun listMemoRevisions(
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

        internal suspend fun recordRevisionRestoreHandoff(command: MemoLifecycleCommand) {
            require(command.operation == MemoLifecycleOperation.VERSION_RESTORE) {
                "Revision restore history handoff requires version-restore command: " +
                    command.metadata.operationId.value
            }
            val target = requireNotNull(command.revisionRestoreTarget) {
                "Revision restore history handoff requires target revision: ${command.metadata.operationId.value}"
            }
            val restoredRevision =
                requireNotNull(store.getRevision(target.revisionId)) {
                    "Revision not found for restore history handoff: ${target.revisionId}"
                }
            appendRestoredRevision(
                restoredRevision = restoredRevision,
                rawContent = target.rawContent,
                store = store,
                memoTextProcessor = memoTextProcessor,
                runInTransaction = runInTransaction,
                loadSnapshotSettings = loadSnapshotSettings,
                now = now,
                nextCommitId = nextCommitId,
                nextRevisionId = nextRevisionId,
                pruneRevisionsForMemo = ::pruneRevisionsForMemo,
            )
        }

        suspend fun clearAllMemoSnapshots() {
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

internal data class MemoRevisionRestoreAsset(
    val category: WorkspaceMediaCategory,
    val filename: String,
    val writeTo: suspend (OutputStream) -> Unit,
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
internal val AUDIO_EXTENSIONS = MediaFileExtensions.AUDIO
internal val IMAGE_EXTENSIONS = MediaFileExtensions.IMAGE
