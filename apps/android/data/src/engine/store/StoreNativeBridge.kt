package com.lomo.data.engine.store

import com.lomo.nativebridge.StoreMemoCommand as BridgeMemoCommand
import com.lomo.nativebridge.StoreMemoCommit as BridgeMemoCommit
import com.lomo.nativebridge.StoreMemoPage as BridgeMemoPage
import com.lomo.nativebridge.StoreMemoQuery as BridgeMemoQuery
import com.lomo.nativebridge.StoreMemoSnapshot as BridgeMemoSnapshot
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

    fun sidebarProjection(): BridgeSidebarProjection
}

/** Mutation and lifecycle JNI capabilities. */
internal interface StoreNativeMutationBridge {
    /** Commits Rust-parsed facts after a workspace document command (Direct or SAF). */
    fun commitWorkspaceDocumentFacts(
        command: BridgeMemoCommand,
        projection: BridgeSafMemoProjection,
    ): BridgeMemoCommit

    fun startRebuild(batchSize: UInt): BridgeRebuildResult
}

/** Complete store FFI boundary. Read and mutation capabilities stay explicit sub-interfaces. */
internal interface StoreNativeBridge : StoreNativeReadBridge, StoreNativeMutationBridge

// MediaNativeBridge / ArchiveNativeBridge live under engine.media / engine.archive.
