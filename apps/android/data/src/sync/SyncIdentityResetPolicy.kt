package com.lomo.data.sync

import com.lomo.data.worker.DeferredLockWorkStore
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.repository.SyncStateResetRepository

/**
 * Owns the one safe disposal order for identity-scoped durable sync state.
 *
 * The durable `.lomo/sync/v1` control tree is fenced to the exact (workspace generation,
 * remote dataset, canonical remote identity) triple it was minted under. Any settings
 * mutation that alters an identity input — remote URL/endpoint, branch, author, bucket,
 * prefix, region, access key, username, or the selected backend itself — must dispose the
 * stale-identity surfaces **before** persisting the new value:
 *
 * 1. [RustSyncScheduler.cancel] stops queued/running work whose WorkManager input carries a
 *    stale config snapshot;
 * 2. [DeferredLockWorkStore.clear] drops the serialized deferred lock-work blob — it would
 *    resume a cycle under the old identity;
 * 3. [SyncStateResetRepository.resetWorkspaceScopedSyncState] removes the durable control
 *    tree and pending review state recorded under the old fence.
 *
 * A missed disposal never corrupts remote data: the durable fence fails closed with
 * `sync_identity_mismatch` and this policy is the explicit recovery lever — cycles never
 * clean-slate durable state implicitly.
 */
class SyncIdentityResetPolicy(
    private val scheduler: RustSyncScheduler,
    private val deferredLockStore: DeferredLockWorkStore,
    private val syncStateReset: SyncStateResetRepository,
) {
    /** Cancels live/queued work, clears deferred inputs, resets the durable control tree. */
    suspend fun resetIdentityScopedSyncState() {
        scheduler.cancel()
        deferredLockStore.clear()
        syncStateReset.resetWorkspaceScopedSyncState()
    }
}
