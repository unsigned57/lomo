package com.lomo.data.engine.store

import com.lomo.nativebridge.StoreHistoryAttachmentRef as BridgeHistoryAttachmentRef
import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommit as BridgeMemoCommit
import com.lomo.nativebridge.StoreMemoBatchDelete as BridgeMemoBatchDelete
import com.lomo.nativebridge.StoreMemoBatchCommit as BridgeMemoBatchCommit
import com.lomo.nativebridge.StoreMemoPage as BridgeMemoPage
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StoreMemoSnapshot as BridgeMemoSnapshot
import com.lomo.nativebridge.StoreMemoStatisticsRow as BridgeMemoStatisticsRow
import com.lomo.nativebridge.StoreSafMemoCreateBegin as BridgeSafMemoCreateBegin
import com.lomo.nativebridge.StoreSafMemoCreateBeginResult as BridgeSafMemoCreateBeginResult
import com.lomo.nativebridge.StoreSafMemoRollbackResult as BridgeSafMemoRollbackResult
import com.lomo.nativebridge.StoreSidebarProjection as BridgeSidebarProjection
import com.lomo.nativebridge.StoreReminderPlan as BridgeReminderPlan
import com.lomo.nativebridge.StoreReminderQuery as BridgeReminderQuery
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import com.lomo.nativebridge.StoreRebuildResult as BridgeRebuildResult
import com.lomo.nativebridge.StoreSafMemoProjection as BridgeSafMemoProjection

/**
 * True FFI edge for store operations.
 *
 * Production: [com.lomo.data.engine.ManagedEngineSession] / workspace adapter.
 * Host tests inject fakes so [BoltFfiStorePort] mapping is exercised without JNI.
 *
 * Dual-stack Kotlin SQLite is forbidden — this surface is mapping + dispatch only.
 */
/** Read-only JNI capabilities. */
internal interface StoreNativeReadBridge {
    fun queryMemos(
        query: BridgeMemoQuery,
        cursor: BridgePageCursor?,
        pageSize: UInt,
        startMemoId: String?,
        backward: Boolean,
    ): BridgeMemoPage

    fun getMemo(memoId: String): BridgeMemoSnapshot?

    /** Counts the exact query predicate without transferring page rows. */
    fun queryCount(query: BridgeMemoQuery): ULong

    /** Lets Rust select memo-bound staged media before the platform executes any promotion. */
    fun selectMemoPromotePlans(
        content: String,
        candidates: List<com.lomo.nativebridge.MediaPromotePlanDto>,
    ): List<com.lomo.nativebridge.MediaPromotePlanDto>

    /** Reads compact materialized statistics rows without loading memo bodies. */
    fun memoStatisticsRows(): List<BridgeMemoStatisticsRow>

    fun sourceDocumentFingerprint(sourcePath: String): String?

    fun sidebarProjection(): BridgeSidebarProjection

    fun listHistoryAttachmentRefs(): List<BridgeHistoryAttachmentRef>

    fun listMemoHistory(memoId: String, cursor: String?, limit: UInt): com.lomo.nativebridge.StoreMemoHistoryPage

    fun queryReminderPlan(query: BridgeReminderQuery): BridgeReminderPlan

}

/** Mutation and lifecycle JNI capabilities. */
internal interface StoreNativeMutationBridge {
    /**
     * Applies one memo command.
     *
     * [onPublication] receives bridge commits the engine publishes while the command is still
     * executing — currently the pending-create publication a SAF create emits before its durable
     * platform I/O — so the caller can feed them to its invalidation bus mid-flight.
     */
    fun applyMemoCommand(
        command: BridgeMemoCommand,
        onPublication: (BridgeMemoCommit) -> Unit,
    ): BridgeMemoCommit

    /** Permanently deletes a bounded trash batch and publishes one projection revision. */
    fun permanentDeleteMany(request: BridgeMemoBatchDelete): BridgeMemoBatchCommit

    /** Commits verified SAF platform results for the whole batch in one projection transaction. */
    fun commitSafPermanentDeleteMany(request: BridgeMemoBatchDelete): BridgeMemoBatchCommit

    fun commitSafProjectionMutation(
        command: BridgeMemoCommand,
        projection: BridgeSafMemoProjection?,
    ): BridgeMemoCommit

    /** Commits Rust-parsed facts after a workspace document command (Direct or SAF). */
    fun commitWorkspaceDocumentFacts(
        command: BridgeMemoCommand,
        projection: BridgeSafMemoProjection,
    ): BridgeMemoCommit

    /** Publishes the pending-create projection and allocates the create identity. */
    fun beginSafMemoCreate(begin: BridgeSafMemoCreateBegin): BridgeSafMemoCreateBeginResult

    /** Removes a begun create's pending projection; `removed=false` when nothing was pending. */
    fun rollbackSafMemoCreate(
        operationId: String,
        memoId: String,
    ): BridgeSafMemoRollbackResult

    fun startRebuild(batchSize: UInt): BridgeRebuildResult
}

/** Complete store FFI boundary. Read and mutation capabilities stay explicit sub-interfaces. */
internal interface StoreNativeBridge : StoreNativeReadBridge, StoreNativeMutationBridge

// MediaNativeBridge / ArchiveNativeBridge live under engine.media / engine.archive.
