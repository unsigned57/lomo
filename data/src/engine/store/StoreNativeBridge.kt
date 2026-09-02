package com.lomo.data.engine.store

import com.lomo.nativebridge.StoreHistoryAttachmentRef as BridgeHistoryAttachmentRef
import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommit as BridgeMemoCommit
import com.lomo.nativebridge.StoreMemoPage as BridgeMemoPage
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StoreMemoSnapshot as BridgeMemoSnapshot
import com.lomo.nativebridge.StoreSafMemoCreateBegin as BridgeSafMemoCreateBegin
import com.lomo.nativebridge.StoreSafMemoCreateBeginResult as BridgeSafMemoCreateBeginResult
import com.lomo.nativebridge.StoreSafMemoRollbackResult as BridgeSafMemoRollbackResult
import com.lomo.nativebridge.StoreSidebarProjection as BridgeSidebarProjection
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
internal interface StoreNativeBridge {
    fun queryMemos(
        query: BridgeMemoQuery,
        cursor: BridgePageCursor?,
        pageSize: UInt,
    ): BridgeMemoPage

    fun getMemo(memoId: String): BridgeMemoSnapshot?

    fun sourceDocumentFingerprint(sourcePath: String): String?

    fun sidebarProjection(): BridgeSidebarProjection

    fun listHistoryAttachmentRefs(): List<BridgeHistoryAttachmentRef>

    fun listMemoHistory(memoId: String, cursor: String?, limit: UInt): com.lomo.nativebridge.StoreMemoHistoryPage =
        error("Store history capability is not available on this bridge")

    /**
     * Applies one memo command.
     *
     * [onPublication] receives bridge commits the engine publishes while the command is still
     * executing — currently the pending-create publication a SAF create emits before its durable
     * platform I/O — so the caller can feed them to its invalidation bus mid-flight.
     */
    fun applyMemoCommand(
        command: BridgeMemoCommand,
        onPublication: (BridgeMemoCommit) -> Unit = {},
    ): BridgeMemoCommit

    fun commitSafProjectionMutation(
        command: BridgeMemoCommand,
        projection: BridgeSafMemoProjection?,
    ): BridgeMemoCommit

    /** Publishes the pending-create projection and allocates the create identity. */
    fun beginSafMemoCreate(begin: BridgeSafMemoCreateBegin): BridgeSafMemoCreateBeginResult =
        error("SAF pending create capability is not available on this bridge")

    /** Removes a begun create's pending projection; `removed=false` when nothing was pending. */
    fun rollbackSafMemoCreate(
        operationId: String,
        memoId: String,
    ): BridgeSafMemoRollbackResult =
        error("SAF pending create capability is not available on this bridge")

    fun startRebuild(batchSize: UInt): BridgeRebuildResult
}

// MediaNativeBridge / ArchiveNativeBridge live under engine.media / engine.archive.
