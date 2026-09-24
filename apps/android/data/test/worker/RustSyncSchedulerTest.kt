package com.lomo.data.worker

/*
 * Behavior Contract:
 * - Unit under test: RustSyncScheduler.enqueueOneShot outcome semantics
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: a one-shot enqueue returns a typed outcome — Accepted only after WorkManager
 *   admits the unique work; invalid prerequisites reject with the real skip reason instead of
 *   silently returning or claiming success.
 *
 * Scenarios:
 * - Given no Direct workspace root, when enqueueOneShot runs, then Rejected(NO_DIRECT_ROOT)
 *   and WorkManager is never touched.
 * - Given a Direct root but backend none/inbox, when enqueueOneShot runs, then
 *   Rejected(NO_ACTIVE_BACKEND).
 * - Given a git backend without remote/branch config, when enqueueOneShot runs, then
 *   Rejected(INCOMPLETE_CONFIG).
 *
 * Observable outcomes: RustSyncEnqueueOutcome reason/workName fields.
 * TDD proof:
 * - Fails before the fix because the enqueue outcome surface does not exist.
 * Excludes: WorkManager admission itself (needs an instrumented Context); the Accepted arm is
 * covered by worker composition/FFI contracts.
 */

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.MemorySecretMaterialSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class RustSyncSchedulerTest : DataFunSpec() {
    init {
        test("no direct workspace root rejects with NO_DIRECT_ROOT") {
            runTest {
                val scheduler =
                    RustSyncScheduler(
                        context = mockk<Context>(),
                        dataStore = createLomoDataStore(backgroundScope),
                        workspaceRoot = WorkspaceFilesystemRoot { null },
                        identityMaterial = MemorySecretMaterialSource(),
                    )

                val outcome = scheduler.enqueueOneShot(secretFieldKey = null)

                outcome shouldBe
                    RustSyncEnqueueOutcome.Rejected(RustSyncEnqueueRejection.NO_DIRECT_ROOT)
            }
        }

        test("inbox backend rejects with NO_ACTIVE_BACKEND") {
            runTest {
                val dataStore = createLomoDataStore(backgroundScope)
                dataStore.setRemoteSyncBackendType("inbox")
                val scheduler =
                    RustSyncScheduler(
                        context = mockk<Context>(),
                        dataStore = dataStore,
                        workspaceRoot = WorkspaceFilesystemRoot { "/workspaces/notes" },
                        identityMaterial = MemorySecretMaterialSource(),
                    )

                val outcome = scheduler.enqueueOneShot(secretFieldKey = null)

                outcome shouldBe
                    RustSyncEnqueueOutcome.Rejected(RustSyncEnqueueRejection.NO_ACTIVE_BACKEND)
            }
        }

        test("git backend without remote config rejects with INCOMPLETE_CONFIG") {
            runTest {
                val dataStore = createLomoDataStore(backgroundScope)
                dataStore.setRemoteSyncBackendType("git")
                val scheduler =
                    RustSyncScheduler(
                        context = mockk<Context>(),
                        dataStore = dataStore,
                        workspaceRoot = WorkspaceFilesystemRoot { "/workspaces/notes" },
                        identityMaterial = MemorySecretMaterialSource(),
                    )

                val outcome = scheduler.enqueueOneShot(secretFieldKey = null)

                outcome shouldBe
                    RustSyncEnqueueOutcome.Rejected(RustSyncEnqueueRejection.INCOMPLETE_CONFIG)
            }
        }
    }
}

private fun createLomoDataStore(scope: CoroutineScope): LomoDataStore {
    val backingFile =
        Files.createTempFile("lomo-sync-scheduler", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
    val realDataStore =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
    val constructor =
        LomoDataStore::class.java.getDeclaredConstructor(DataStore::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(realDataStore)
}
