package com.lomo.data.repository

import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import java.io.File

internal suspend fun persistResolvedRevisionAssets(
    store: MemoVersionStore,
    blobRoot: File,
    createdAt: Long,
    assets: List<ResolvedMemoRevisionAsset>,
    newlyPersistedBlobHashes: MutableSet<String>,
): List<MemoVersionAssetRecord> =
    assets.map { asset ->
        trackAndPersistMemoVersionBlobIfNeeded(
            store = store,
            blobRoot = blobRoot,
            bytes = asset.bytes,
            contentEncoding = asset.contentEncoding,
            createdAt = createdAt,
            newlyPersistedBlobHashes = newlyPersistedBlobHashes,
        )
        MemoVersionAssetRecord(
            revisionId = UNASSIGNED_REVISION_ID,
            logicalPath = asset.logicalPath,
            blobHash = asset.blobHash,
            contentEncoding = asset.contentEncoding,
        )
    }

internal suspend fun trackAndPersistMemoVersionBlobIfNeeded(
    store: MemoVersionStore,
    blobRoot: File,
    bytes: ByteArray,
    contentEncoding: String,
    createdAt: Long,
    newlyPersistedBlobHashes: MutableSet<String>,
): String {
    val blobHash = bytes.toVersionHash()
    if (store.getBlob(blobHash) == null) {
        newlyPersistedBlobHashes += blobHash
    }
    return persistMemoVersionBlobIfNeeded(
        store = store,
        blobRoot = blobRoot,
        bytes = bytes,
        contentEncoding = contentEncoding,
        createdAt = createdAt,
    )
}

internal suspend fun insertMemoVersionRevision(
    store: MemoVersionStore,
    memoState: MemoVersionMemoState,
    lifecycleState: MemoRevisionLifecycleState,
    origin: MemoRevisionOrigin,
    sharedCommit: MemoVersionCommitRecord?,
    latestRevisionId: String?,
    rawMarkdownBlobHash: String,
    rawContentHash: String,
    assetFingerprint: String,
    createdAt: Long,
    nextCommitId: () -> String,
    nextRevisionId: () -> String,
    persistedAssets: List<MemoVersionAssetRecord>,
) {
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
            parentRevisionId = latestRevisionId,
            commitId = commit.commitId,
            dateKey = memoState.dateKey,
            lifecycleState = lifecycleState,
            rawMarkdownBlobHash = rawMarkdownBlobHash,
            contentHash = rawContentHash,
            assetFingerprint = assetFingerprint,
            memoTimestamp = memoState.timestamp,
            memoUpdatedAt = memoState.updatedAt,
            memoContent = buildMemoRevisionPreview(memoState.content),
            createdAt = createdAt,
        ),
    )
    store.replaceAssets(
        revisionId = revisionId,
        records = persistedAssets.map { asset -> asset.copy(revisionId = revisionId) },
    )
}

internal suspend fun cleanupMemoVersionBlobWriteFailures(
    store: MemoVersionStore,
    blobRoot: File,
    blobHashes: Iterable<String>,
) {
    blobHashes.forEach { blobHash ->
        cleanupMemoVersionBlobWriteFailure(
            store = store,
            blobRoot = blobRoot,
            blobHash = blobHash,
        )
    }
}

internal suspend fun appendRestoredRevision(
    restoredRevision: MemoVersionRevisionRecord,
    rawContent: String,
    store: MemoVersionStore,
    memoTextProcessor: MemoTextProcessor,
    runInTransaction: suspend (suspend () -> Unit) -> Unit,
    loadSnapshotSettings: suspend () -> MemoSnapshotRetentionSettings,
    now: () -> Long,
    nextCommitId: () -> String,
    nextRevisionId: () -> String,
    pruneRevisionsForMemo: suspend (String, MemoSnapshotRetentionSettings, Long) -> Unit,
) {
    val snapshotSettings = loadSnapshotSettings()
    if (!snapshotSettings.enabled) {
        return
    }
    val restoredMemo = restoredRevision.toMemo(rawContent, memoTextProcessor)
    val memoState = MemoVersionMemoState.fromMemo(restoredMemo)
    val persistedAssets = store.listAssetsForRevision(restoredRevision.revisionId)
    val assetPairs = persistedAssets.map(MemoVersionAssetRecord::pair)
    val assetFingerprint =
        restoredRevision.assetFingerprint ?: assetPairs.toMemoVersionAssetFingerprint()

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
                rawContentHash = restoredRevision.contentHash,
                lifecycleState = restoredRevision.lifecycleState,
                assetFingerprint = assetFingerprint,
                assetPairs = assetPairs,
                latestAssetPairs = latestAssetPairs,
            )
        ) {
            return@runInTransaction
        }
        if (
            store.hasEquivalentHistoricalRevision(
                memoId = memoState.memoId,
                lifecycleState = restoredRevision.lifecycleState,
                rawMarkdownBlobHash = restoredRevision.rawMarkdownBlobHash,
                contentHash = restoredRevision.contentHash,
                assetFingerprint = assetFingerprint,
                assetPairs = assetPairs,
            )
        ) {
            return@runInTransaction
        }
        insertMemoVersionRevision(
            store = store,
            memoState = memoState,
            lifecycleState = restoredRevision.lifecycleState,
            origin = MemoRevisionOrigin.LOCAL_RESTORE,
            sharedCommit = null,
            latestRevisionId = latestRevision?.revisionId,
            rawMarkdownBlobHash = restoredRevision.rawMarkdownBlobHash,
            rawContentHash = restoredRevision.contentHash,
            assetFingerprint = assetFingerprint,
            createdAt = createdAt,
            nextCommitId = nextCommitId,
            nextRevisionId = nextRevisionId,
            persistedAssets =
                persistedAssets.map { asset ->
                    asset.copy(revisionId = UNASSIGNED_REVISION_ID)
                },
        )
        pruneRevisionsForMemo(memoState.memoId, snapshotSettings, createdAt)
    }
}

internal fun nextMemoVersionCreatedAt(
    latestRevision: MemoVersionRevisionRecord?,
    now: () -> Long,
): Long =
    latestRevision
        ?.createdAt
        ?.let { latestCreatedAt ->
            maxOf(now(), latestCreatedAt + 1)
        } ?: now()

internal suspend fun listRevisionAssetPairs(
    store: MemoVersionStore,
    revision: MemoVersionRevisionRecord?,
): List<Pair<String, String>> =
    revision
        ?.let { currentRevision ->
            store.listAssetsForRevision(currentRevision.revisionId).map(MemoVersionAssetRecord::pair)
        }.orEmpty()
