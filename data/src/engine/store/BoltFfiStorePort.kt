package com.lomo.data.engine.store

import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommandKind as BridgeMemoCommandKind
import com.lomo.nativebridge.StoreMemoFilters as BridgeMemoFilters
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import java.util.UUID

/**
 * Production [StorePort] over [StoreNativeBridge] (ManagedEngineSession / BoltFFI).
 *
 * Requires a Direct workspace (store handle). Missing store handle fails closed from native.
 * Mapping logic is host-testable via fake bridges; real JNI stays behind the bridge only.
 */
internal class BoltFfiStorePort(
    private val bridge: StoreNativeBridge,
) : StorePort {
    override fun queryMemos(
        query: StoreMemoQuery,
        cursor: StorePageCursor?,
        pageSize: Int,
    ): StoreMemoPage {
        val page =
            bridge.queryMemos(
                BridgeMemoQuery(
                    searchText = query.searchText,
                    filters =
                        BridgeMemoFilters(
                            tag = query.filters.tag,
                            dateFromMs = query.filters.dateFromMs,
                            dateToMs = query.filters.dateToMs,
                            hasTodo = query.filters.hasTodo,
                            hasAttachment = query.filters.hasAttachment,
                            hasUrl = query.filters.hasUrl,
                            pinnedOnly = query.filters.pinnedOnly,
                            includeTrash = query.filters.includeTrash,
                            trashOnly = query.filters.trashOnly,
                        ),
                ),
                cursor?.let { BridgePageCursor(encoded = it.encoded) },
                pageSize.toUInt(),
            )
        return StoreMemoPage(
            items = page.items.map { it.toSummary() },
            nextCursor = page.nextCursor?.let { StorePageCursor(encoded = it.encoded) },
            highWaterRevision = page.highWaterRevision.toLong(),
            queryFingerprint = page.queryFingerprint,
        )
    }

    override fun getMemo(memoId: String): StoreMemoSnapshot? {
        val snap = bridge.getMemo(memoId) ?: return null
        return StoreMemoSnapshot(summary = snap.summary.toSummary(), body = snap.body)
    }

    override fun applyMemoCommand(command: StoreMemoCommand): StoreMemoCommit {
        val result =
            bridge.applyMemoCommand(
                BridgeMemoCommand(
                    operationId = command.operationId.ifBlank { UUID.randomUUID().toString() },
                    kind = command.kind.toBridge(),
                    memoId = command.memoId,
                    expectedRevision = command.expectedRevision.toULong(),
                    expectedFingerprint = command.expectedFingerprint,
                    content = command.content,
                    tags = command.tags,
                    pin = command.pin,
                ),
            )
        return StoreMemoCommit(
            operationId = result.operationId,
            memoId = result.memoId,
            coreRevision = result.coreRevision.toLong(),
            eventSequence = result.eventSequence.toLong(),
            contentRevision = result.contentRevision.toLong(),
            fileFingerprint = result.fileFingerprint,
            scopes = result.scopes,
            idempotentReplay = result.idempotentReplay,
        )
    }

    override fun startRebuild(batchSize: Int): StoreRebuildResult {
        val result = bridge.startRebuild(batchSize.toUInt())
        return StoreRebuildResult(
            memosIndexed = result.memosIndexed.toLong(),
            fileCount = result.fileCount.toLong(),
            attachmentCount = result.attachmentCount.toLong(),
            workspaceDigest = result.workspaceDigest,
            storeDigest = result.storeDigest,
            corruptLomoIsolated = result.corruptLomoIsolated.toLong(),
            highWaterRevision = result.highWaterRevision.toLong(),
        )
    }

    private fun com.lomo.nativebridge.StoreMemoSummary.toSummary(): StoreMemoSummary =
        StoreMemoSummary(
            memoId = memoId,
            sourcePath = sourcePath,
            fileFingerprint = fileFingerprint,
            updatedAtMs = updatedAtMs,
            createdAtMs = createdAtMs,
            hasTodo = hasTodo,
            hasUrl = hasUrl,
            hasAttachment = hasAttachment,
            isPinned = isPinned,
            isTrashed = isTrashed,
            bodyPreview = bodyPreview,
            contentRevision = contentRevision.toLong(),
            rank = rank,
            tags = tags,
            imageUrls = imageUrls,
        )

    private fun StoreMemoCommandKind.toBridge(): BridgeMemoCommandKind =
        when (this) {
            StoreMemoCommandKind.Create -> BridgeMemoCommandKind.CREATE
            StoreMemoCommandKind.Update -> BridgeMemoCommandKind.UPDATE
            StoreMemoCommandKind.Delete -> BridgeMemoCommandKind.DELETE
            StoreMemoCommandKind.Restore -> BridgeMemoCommandKind.RESTORE
            StoreMemoCommandKind.Pin -> BridgeMemoCommandKind.PIN
            StoreMemoCommandKind.Unpin -> BridgeMemoCommandKind.UNPIN
            StoreMemoCommandKind.HistoryRestore -> BridgeMemoCommandKind.HISTORY_RESTORE
        }
}
