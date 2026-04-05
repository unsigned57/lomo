package com.lomo.data.repository

import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import java.io.File

internal suspend fun captureRevisionAssets(
    memoState: MemoVersionMemoState,
    memoTextProcessor: MemoTextProcessor,
    workspaceMediaAccess: WorkspaceMediaAccess,
): List<ResolvedMemoRevisionAsset> =
    memoTextProcessor
        .extractLocalAttachmentPaths(memoState.content)
        .mapNotNull { path ->
            val resolved =
                resolveRevisionAttachment(
                    path = path,
                    workspaceMediaAccess = workspaceMediaAccess,
                ) ?: return@mapNotNull null
            ResolvedMemoRevisionAsset(
                logicalPath = resolved.logicalPath,
                contentEncoding = resolved.contentEncoding,
                blobHash = resolved.bytes.toVersionHash(),
                bytes = resolved.bytes,
            )
        }.sortedBy(ResolvedMemoRevisionAsset::logicalPath)

internal suspend fun restoreRevisionAssets(
    revisionId: String,
    store: MemoVersionStore,
    blobRoot: File,
    workspaceMediaAccess: WorkspaceMediaAccess,
) {
    store.listAssetsForRevision(revisionId).forEach { asset ->
        val category = asset.logicalPath.toAttachmentCategory() ?: return@forEach
        val filename = asset.logicalPath.substringAfterLast('/')
        val bytes =
            readMemoVersionBlobBytes(
                store = store,
                blobRoot = blobRoot,
                blobHash = asset.blobHash,
            )
        workspaceMediaAccess.writeFile(
            category = category,
            filename = filename,
            bytes = bytes,
        )
    }
}

internal fun MemoVersionRevisionRecord.toMemo(
    rawContent: String,
    memoTextProcessor: MemoTextProcessor,
): Memo {
    val resolvedBody = resolveMemoRevisionBody(rawContent, memoContent, memoTimestamp, dateKey)
    return Memo(
        id = memoId,
        timestamp = resolvedBody.timestamp,
        updatedAt = memoUpdatedAt,
        content = resolvedBody.content,
        rawContent = rawContent,
        dateKey = dateKey,
        tags = memoTextProcessor.extractTags(resolvedBody.content),
        imageUrls = memoTextProcessor.extractImages(resolvedBody.content),
        isDeleted = lifecycleState != MemoRevisionLifecycleState.ACTIVE,
    )
}

internal fun Memo.currentLifecycleState(): MemoRevisionLifecycleState =
    if (isDeleted) MemoRevisionLifecycleState.TRASHED else MemoRevisionLifecycleState.ACTIVE

internal fun MemoVersionAssetRecord.pair(): Pair<String, String> = logicalPath to blobHash

internal fun ResolvedMemoRevisionAsset.pair(): Pair<String, String> = logicalPath to blobHash

internal fun List<Pair<String, String>>.toMemoVersionAssetFingerprint(): String =
    joinToString(separator = "\n") { (logicalPath, blobHash) -> "$logicalPath:$blobHash" }.toVersionHash()

internal fun MemoVersionRevisionRecord?.matchesCurrentState(
    rawContentHash: String,
    lifecycleState: MemoRevisionLifecycleState,
    assetFingerprint: String,
    assetPairs: List<Pair<String, String>>,
    latestAssetPairs: List<Pair<String, String>>,
): Boolean =
    this != null &&
        contentHash == rawContentHash &&
        this.lifecycleState == lifecycleState &&
        (
            this.assetFingerprint?.let { latestFingerprint -> latestFingerprint == assetFingerprint } ?:
                (latestAssetPairs == assetPairs)
        )

internal suspend fun MemoVersionStore.hasEquivalentHistoricalRevision(
    memoId: String,
    lifecycleState: MemoRevisionLifecycleState,
    rawMarkdownBlobHash: String,
    contentHash: String,
    assetFingerprint: String,
    assetPairs: List<Pair<String, String>>,
): Boolean =
    findEquivalentRevisionsForMemo(
        memoId = memoId,
        lifecycleState = lifecycleState,
        rawMarkdownBlobHash = rawMarkdownBlobHash,
        contentHash = contentHash,
        assetFingerprint = assetFingerprint,
    ).let { candidates ->
        if (candidates.any { revision -> revision.assetFingerprint == assetFingerprint }) {
            true
        } else {
            candidates
                .filter { revision -> revision.assetFingerprint == null }
                .any { revision ->
                    listAssetsForRevision(revision.revisionId).map(MemoVersionAssetRecord::pair) == assetPairs
                }
        }
    }

internal suspend fun rollbackCurrentMemoState(
    currentMemo: Memo,
    persistActiveMemo: suspend (Memo) -> Unit,
    persistTrashedMemo: suspend (Memo) -> Unit,
) {
    when (currentMemo.currentLifecycleState()) {
        MemoRevisionLifecycleState.ACTIVE -> persistActiveMemo(currentMemo.copy(isDeleted = false))
        MemoRevisionLifecycleState.TRASHED,
        MemoRevisionLifecycleState.DELETED -> persistTrashedMemo(currentMemo.copy(isDeleted = true))
    }
}

internal fun summaryFor(
    origin: MemoRevisionOrigin,
    lifecycleState: MemoRevisionLifecycleState,
): String =
    when (origin) {
        MemoRevisionOrigin.LOCAL_CREATE -> "Created memo"
        MemoRevisionOrigin.LOCAL_EDIT -> "Edited memo"
        MemoRevisionOrigin.LOCAL_TRASH -> "Moved to trash"
        MemoRevisionOrigin.LOCAL_RESTORE ->
            when (lifecycleState) {
                MemoRevisionLifecycleState.ACTIVE -> "Restored version"
                MemoRevisionLifecycleState.TRASHED -> "Restored trashed version"
                MemoRevisionLifecycleState.DELETED -> "Restored deleted version"
            }
        MemoRevisionOrigin.LOCAL_DELETE -> "Deleted memo"
        MemoRevisionOrigin.IMPORT_REFRESH ->
            when (lifecycleState) {
                MemoRevisionLifecycleState.ACTIVE -> "Imported external change"
                MemoRevisionLifecycleState.TRASHED -> "Imported trash change"
                MemoRevisionLifecycleState.DELETED -> "Imported deletion"
            }
        MemoRevisionOrigin.IMPORT_SYNC ->
            when (lifecycleState) {
                MemoRevisionLifecycleState.ACTIVE -> "Imported sync change"
                MemoRevisionLifecycleState.TRASHED -> "Imported sync trash change"
                MemoRevisionLifecycleState.DELETED -> "Imported sync deletion"
            }
    }

internal data class ResolvedMemoRevisionAsset(
    val logicalPath: String,
    val contentEncoding: String,
    val blobHash: String,
    val bytes: ByteArray,
)
