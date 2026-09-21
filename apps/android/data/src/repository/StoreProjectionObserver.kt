package com.lomo.data.repository

import com.lomo.data.engine.store.StoreMemoBatchCommit
import com.lomo.data.engine.store.StoreMemoCommit
import com.lomo.data.engine.store.StoreRebuildResult
import com.lomo.data.engine.store.toStoreCommit

/**
 * Single data-adapter observer for owner projection stamps.
 *
 * Repositories execute commands; this type is the only writer onto [StoreInvalidationBus] for
 * those stamps. Native receipts map here; Kotlin does not mint a second publication clock.
 */
internal class StoreProjectionObserver(
    private val bus: StoreInvalidationBus,
) {
    fun observeCommit(
        commit: StoreMemoCommit,
        midFlightAlreadyPublished: Boolean = false,
    ) {
        if (midFlightAlreadyPublished) {
            bus.confirm(commit)
        } else {
            bus.publish(commit)
        }
    }

    fun observeBatch(commit: StoreMemoBatchCommit) {
        bus.publishBatchCommit(commit)
    }

    fun observeRebuild(result: StoreRebuildResult) {
        if (result.rewritten) {
            bus.publishRebuild(result.highWaterRevision)
        }
    }

    fun observeNativeCommit(commit: com.lomo.nativebridge.StoreMemoCommit) {
        observeCommit(commit.toStoreCommit())
    }
}
