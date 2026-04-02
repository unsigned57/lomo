package com.lomo.data.repository

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
