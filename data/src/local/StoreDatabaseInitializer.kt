package com.lomo.data.local

import android.content.Context
import com.lomo.data.engine.store.StorePort
import com.lomo.data.repository.RoomCutover
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.domain.repository.DatabaseInitializationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * P3-10 cutover + store readiness: drain check → delete Room file → rebuild Rust projections.
 *
 * Replaces Room [DatabaseInitializer]. Never opens Room / androidx.room.
 */
class StoreDatabaseInitializer(
    private val context: Context,
    private val port: StorePort,
    private val invalidation: StoreInvalidationBus,
) : DatabaseInitializationRepository {
    private val mutex = Mutex()
    private var ready = false

    override suspend fun ensureReady() {
        if (ready) return
        mutex.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                // Freeze writes is the caller's responsibility before ensureReady; rebuild gate then
                // rejects mutations while rebuilding. Undrained outbox fails closed before rebuild/delete.
                RoomCutover.assertMemoOutboxDrainedOrAbsent(context).getOrThrow()
                // Rebuild from durable Markdown/media/.lomo facts (clean-slate; no Room pin/history migrate).
                // Compare workspace scan vs store projection (counts + digests) before discarding legacy.
                val rebuild = port.startRebuild(batchSize = 64)
                RoomCutover
                    .assertCutoverCompare(
                        RoomCutover.CutoverCompareEvidence(
                            memoCount = rebuild.memosIndexed,
                            fileCount = rebuild.fileCount,
                            attachmentCount = rebuild.attachmentCount,
                            workspaceDigest = rebuild.workspaceDigest,
                            storeDigest = rebuild.storeDigest,
                        ),
                    ).getOrThrow()
                RoomCutover.deleteLegacyRoomDatabase(context)
                invalidation.bump()
            }
            ready = true
        }
    }
}
