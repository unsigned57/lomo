package com.lomo.data.engine.store

import com.lomo.nativebridge.StoreHistoryAttachmentRef as BridgeHistoryAttachmentRef
import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommit as BridgeMemoCommit
import com.lomo.nativebridge.StoreMemoPage as BridgeMemoPage
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StoreMemoSnapshot as BridgeMemoSnapshot
import com.lomo.nativebridge.StorePageCursor as BridgePageCursor
import com.lomo.nativebridge.StoreRebuildResult as BridgeRebuildResult

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

    fun listHistoryAttachmentRefs(): List<BridgeHistoryAttachmentRef>

    fun applyMemoCommand(command: BridgeMemoCommand): BridgeMemoCommit

    fun startRebuild(batchSize: UInt): BridgeRebuildResult
}

// MediaNativeBridge / ArchiveNativeBridge live under engine.media / engine.archive.
