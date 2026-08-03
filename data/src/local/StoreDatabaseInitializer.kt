package com.lomo.data.local

import com.lomo.data.engine.store.StorePort
import com.lomo.data.repository.StoreInvalidationBus
import com.lomo.domain.repository.DatabaseInitializationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Establishes store readiness by rebuilding the derived Rust projection from durable workspace
 * facts. The Rust rebuild owns integrity and workspace/store count + digest comparison.
 */
class StoreDatabaseInitializer(
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
                // Freeze writes is the caller's responsibility. Rust rejects mutations while
                // rebuilding and fails before replacement if integrity or compare checks diverge.
                port.startRebuild(batchSize = 64)
                invalidation.bump()
            }
            ready = true
        }
    }
}
