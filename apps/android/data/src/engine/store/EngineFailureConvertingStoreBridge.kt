package com.lomo.data.engine.store

import com.lomo.data.engine.withEngineFailureConversion
import com.lomo.nativebridge.StoreHistoryAttachmentRef as BridgeHistoryAttachmentRef
import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommit as BridgeMemoCommit
import com.lomo.nativebridge.StoreMemoBatchDelete as BridgeMemoBatchDelete
import com.lomo.nativebridge.StoreMemoBatchCommit as BridgeMemoBatchCommit
import com.lomo.nativebridge.StoreMemoPage as BridgeMemoPage
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StoreMemoSnapshot as BridgeMemoSnapshot
import com.lomo.nativebridge.StoreMemoStatisticsRow as BridgeMemoStatisticsRow
import com.lomo.nativebridge.StoreSidebarProjection as BridgeSidebarProjection
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import com.lomo.nativebridge.StoreRebuildResult as BridgeRebuildResult
import com.lomo.nativebridge.StoreReminderPlan as BridgeReminderPlan
import com.lomo.nativebridge.StoreReminderQuery as BridgeReminderQuery
import com.lomo.nativebridge.StoreSafMemoProjection as BridgeSafMemoProjection

/**
 * Converts every store FFI rejection into the typed domain failure.
 *
 * The generated `EngineError` carrier has no message, so a rejection that escapes untyped reaches
 * the UI as a blank failure. Implementing the whole bridge interface here makes the conversion
 * compiler-enforced: a new bridge method cannot be added without deciding it crosses this edge.
 */
internal class EngineFailureConvertingStoreBridge(
    private val delegate: StoreNativeBridge,
) : StoreNativeBridge {
    override fun queryMemos(
        query: BridgeMemoQuery,
        cursor: BridgePageCursor?,
        pageSize: UInt,
        startMemoId: String?,
        backward: Boolean,
    ): BridgeMemoPage =
        withEngineFailureConversion {
            delegate.queryMemos(query, cursor, pageSize, startMemoId, backward)
        }

    override fun getMemo(memoId: String): BridgeMemoSnapshot? = withEngineFailureConversion { delegate.getMemo(memoId) }

    override fun queryCount(query: BridgeMemoQuery): ULong =
        withEngineFailureConversion { delegate.queryCount(query) }

    override fun selectMemoPromotePlans(
        content: String,
        candidates: List<com.lomo.nativebridge.MediaPromotePlanDto>,
    ): List<com.lomo.nativebridge.MediaPromotePlanDto> =
        withEngineFailureConversion { delegate.selectMemoPromotePlans(content, candidates) }

    override fun memoStatisticsRows(): List<BridgeMemoStatisticsRow> =
        withEngineFailureConversion { delegate.memoStatisticsRows() }

    override fun sourceDocumentFingerprint(sourcePath: String): String? =
        withEngineFailureConversion { delegate.sourceDocumentFingerprint(sourcePath) }

    override fun sidebarProjection(): BridgeSidebarProjection =
        withEngineFailureConversion { delegate.sidebarProjection() }

    override fun listHistoryAttachmentRefs(): List<BridgeHistoryAttachmentRef> =
        withEngineFailureConversion { delegate.listHistoryAttachmentRefs() }

    override fun listMemoHistory(
        memoId: String,
        cursor: String?,
        limit: UInt,
    ): com.lomo.nativebridge.StoreMemoHistoryPage =
        withEngineFailureConversion { delegate.listMemoHistory(memoId, cursor, limit) }

    override fun queryReminderPlan(query: BridgeReminderQuery): BridgeReminderPlan =
        withEngineFailureConversion { delegate.queryReminderPlan(query) }

    override fun applyMemoCommand(
        command: BridgeMemoCommand,
        onPublication: (BridgeMemoCommit) -> Unit,
    ): BridgeMemoCommit = withEngineFailureConversion { delegate.applyMemoCommand(command, onPublication) }

    override fun permanentDeleteMany(request: BridgeMemoBatchDelete): BridgeMemoBatchCommit =
        withEngineFailureConversion { delegate.permanentDeleteMany(request) }

    override fun commitSafPermanentDeleteMany(request: BridgeMemoBatchDelete): BridgeMemoBatchCommit =
        withEngineFailureConversion { delegate.commitSafPermanentDeleteMany(request) }

    override fun commitSafProjectionMutation(
        command: BridgeMemoCommand,
        projection: BridgeSafMemoProjection?,
    ): BridgeMemoCommit = withEngineFailureConversion { delegate.commitSafProjectionMutation(command, projection) }

    override fun commitWorkspaceDocumentFacts(
        command: BridgeMemoCommand,
        projection: BridgeSafMemoProjection,
    ): BridgeMemoCommit =
        withEngineFailureConversion { delegate.commitWorkspaceDocumentFacts(command, projection) }

    override fun beginSafMemoCreate(
        begin: com.lomo.nativebridge.StoreSafMemoCreateBegin,
    ): com.lomo.nativebridge.StoreSafMemoCreateBeginResult =
        withEngineFailureConversion { delegate.beginSafMemoCreate(begin) }

    override fun rollbackSafMemoCreate(
        operationId: String,
        memoId: String,
    ): com.lomo.nativebridge.StoreSafMemoRollbackResult =
        withEngineFailureConversion { delegate.rollbackSafMemoCreate(operationId, memoId) }

    override fun startRebuild(batchSize: UInt): BridgeRebuildResult =
        withEngineFailureConversion { delegate.startRebuild(batchSize) }
}
