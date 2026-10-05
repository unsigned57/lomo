package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: SyncPolicyRepositoryImpl.setRemoteSyncBackend.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: switching the remote backend disposes identity-scoped durable state through
 *   the shared SyncIdentityResetPolicy order — cancel every RustSyncWorker unique work name,
 *   clear the deferred lock-work blob, then reset the workspace sync control tree; a no-op
 *   same-backend write does none of it.
 *
 * Scenarios:
 * - Given a stored NONE backend, when setRemoteSyncBackend(GIT) runs, then cancel → deferred
 *   clear → reset run in order and the preference becomes git.
 * - Given the backend is already GIT, when setRemoteSyncBackend(GIT) runs again, then no
 *   disposal runs.
 *
 * Observable outcomes: ordered cancel/deferred-clear/reset events; DataStore syncBackendType.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.SyncPolicyRepositoryImplTest'
 * - RED: setRemoteSyncBackend only wrote flags (no cancel, no Rust reset).
 *
 * Excludes: WorkManager device execution; JNI reset of `.lomo/sync/v1`.
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: scheduling decisions keyed on the retired tri-flag preference keys;
 *   backend switch now also clears deferred lock work via the shared SyncIdentityResetPolicy triple.
 * - Why old assertion is no longer correct: syncBackendType is now the single persisted fact and UNKNOWN is an explicit unavailable state;
 *   a deferred blob serialized under the old backend is stale work and must be dropped on switch.
 * - Coverage preserved by: policy cases re-expressed on the single-fact backend model plus the unified disposal order.
 * - Why this is not fitting the test to the implementation: single-fact backend selection is the audit-required contract.
 */

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.Data
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.GitEndpointSecurityMigration
import com.lomo.data.sync.SyncIdentityResetPolicy
import com.lomo.data.worker.CoreSyncScheduler
import com.lomo.data.worker.DeferredLockWorkStore
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.SyncStateResetRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

private fun recordingIdentityReset(events: MutableList<String>): SyncIdentityResetPolicy =
    SyncIdentityResetPolicy(
        scheduler =
            mockk<RustSyncScheduler>().also {
                every { it.cancel() } answers { events += "cancel" }
            },
        deferredLockStore =
            object : DeferredLockWorkStore {
                override fun save(input: Data) = error("not used")

                override fun take(): Data? = null

                override fun clear() {
                    events += "deferred-clear"
                }
            },
        syncStateReset =
            object : SyncStateResetRepository {
                override suspend fun resetWorkspaceScopedSyncState() {
                    events += "reset"
                }
            },
    )

class SyncPolicyRepositoryImplTest : FunSpec({
    test("changing backend cancels rust work then resets the workspace control tree") {
        runTest {
            val events = mutableListOf<String>()
            val dataStore = createLomoDataStore(backgroundScope)
            val repository =
                SyncPolicyRepositoryImpl(
                    dataStore = dataStore,
                    coreSyncScheduler = mockk(),
                    rustSyncScheduler = mockk(),
                    identityReset = recordingIdentityReset(events),
                    gitEndpointSecurityMigration = mockk(),
                )

            repository.setRemoteSyncBackend(SyncBackendType.GIT)

            events shouldBe listOf("cancel", "deferred-clear", "reset")
            dataStore.syncBackendType.first() shouldBe "git"
        }
    }

    test("same backend write does not cancel or reset") {
        runTest {
            val events = mutableListOf<String>()
            val dataStore = createLomoDataStore(backgroundScope)
            dataStore.setRemoteSyncBackendType("git")
            val repository =
                SyncPolicyRepositoryImpl(
                    dataStore = dataStore,
                    coreSyncScheduler = mockk(),
                    rustSyncScheduler = mockk(),
                    identityReset = recordingIdentityReset(events),
                    gitEndpointSecurityMigration = mockk(),
                )

            repository.setRemoteSyncBackend(SyncBackendType.GIT)

            events shouldBe emptyList()
            dataStore.syncBackendType.first() shouldBe "git"
        }
    }

    test("applyRemoteSyncPolicy sanitizes legacy git endpoints before reading the backend") {
        runTest {
            val events = mutableListOf<String>()
            val dataStore = createLomoDataStore(backgroundScope)
            dataStore.setRemoteSyncBackendType("git")
            val scheduler = mockk<RustSyncScheduler>()
            val migration = mockk<GitEndpointSecurityMigration>()
            coEvery { migration.migrateIfNeeded() } answers { events += "migrate" }
            coEvery { scheduler.reschedule() } answers { events += "reschedule" }
            val repository =
                SyncPolicyRepositoryImpl(
                    dataStore = dataStore,
                    coreSyncScheduler = mockk(),
                    rustSyncScheduler = scheduler,
                    identityReset = mockk(),
                    gitEndpointSecurityMigration = migration,
                )

            repository.applyRemoteSyncPolicy()

            events shouldBe listOf("migrate", "reschedule")
        }
    }
})

private fun createLomoDataStore(scope: CoroutineScope): LomoDataStore {
    val backingFile =
        Files.createTempFile("lomo-sync-policy", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
    val realDataStore =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
    val constructor =
        LomoDataStore::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(realDataStore)
}
