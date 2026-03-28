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

internal suspend fun persistResolvedRevisionAssets(
    store: MemoVersionStore,
    blobRoot: File,
    createdAt: Long,
    assets: List<ResolvedMemoRevisionAsset>,
): List<MemoVersionAssetRecord> =
    assets.map { asset ->
        persistMemoVersionBlobIfNeeded(
            store = store,
            blobRoot = blobRoot,
            bytes = asset.bytes,
            contentEncoding = asset.contentEncoding,
            createdAt = createdAt,
        )
        MemoVersionAssetRecord(
            revisionId = UNASSIGNED_REVISION_ID,
            logicalPath = asset.logicalPath,
            blobHash = asset.blobHash,
            contentEncoding = asset.contentEncoding,
        )
    }

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
): Memo =
    Memo(
        id = memoId,
        timestamp = memoTimestamp,
        updatedAt = memoUpdatedAt,
        content = memoContent,
        rawContent = rawContent,
        dateKey = dateKey,
        tags = memoTextProcessor.extractTags(memoContent),
        imageUrls = memoTextProcessor.extractImages(memoContent),
        isDeleted = lifecycleState != MemoRevisionLifecycleState.ACTIVE,
    )

internal fun Memo.currentLifecycleState(): MemoRevisionLifecycleState =
    if (isDeleted) MemoRevisionLifecycleState.TRASHED else MemoRevisionLifecycleState.ACTIVE

internal fun MemoVersionAssetRecord.pair(): Pair<String, String> = logicalPath to blobHash

internal fun ResolvedMemoRevisionAsset.pair(): Pair<String, String> = logicalPath to blobHash

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
