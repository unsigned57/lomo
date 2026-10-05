package com.lomo.data.local.datastore

// adversarial-audit: hypothesis: the corruption quarantine only delivers "evidence +
// notice"; the running session still collapses every recorded choice to defaults, so a corrupted
// preferences file silently turns an explicit sync backend selection into NONE — the next
// applyRemoteSyncPolicy() then cancels the schedule — exactly the "变 NONE/false 并自动取消业务"
// fold the package claims to have removed for unknown/unavailable state.

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: corruption-quarantining preference handler plus backend-selection readers.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: a corrupted preferences file surfaces as an unknown/unavailable state and a
 *   quarantine witness; a recorded backend selection must never silently collapse to NONE.
 *
 * Scenarios:
 * - Given a store file that recorded a git backend before corruption, when it is reopened through
 *   the quarantining handler, then the backend selection reads UNKNOWN, never NONE/false.
 * - Given a corrupted store file, when the handler runs, then evidence is quarantined under a
 *   `.corrupt-` sibling and the registry publishes the notice.
 *
 * Observable outcomes: stored backend selection value, quarantined sibling files, registry notice.
 *
 * TDD proof:
 * - The collapse scenario fails RED when corruption rebuilds on empty preferences without a
 *   witness: a destroyed "git" selection reads as NONE and the scheduler cancels work.
 *
 * Excludes:
 * - Notification presentation of the notice and scheduler policy reactions (app layer).
 */
class PreferencesCorruptionCollapseContractTest : DataFunSpec() {
    init {
        test("corrupted preferences collapse the recorded backend selection to NONE") {
            runTest {
                // Seed a real selection into a healthy store on its own file, then carry the
                // bytes over: the second file genuinely held "git" before being corrupted.
                val seed = Files.createTempFile("corruption-collapse-seed", ".preferences_pb").toFile()
                val healthy = newStore(seed, backgroundScope)
                healthy.edit { it[LomoDataStoreKeys.SYNC_BACKEND_TYPE] = "git" }
                GitSyncBehaviorStoreImpl(healthy)
                    .syncBackendType.first() shouldBe "git"

                val backing = Files.createTempFile("corruption-collapse-backend", ".preferences_pb").toFile()
                seed.copyTo(backing, overwrite = true)

                // Corrupt the file out from under the recorded selection, then reopen through the
                // quarantining handler — the same path the app takes on next launch.
                backing.writeBytes("%%% corrupt %%%".toByteArray())
                val registry = PreferencesCorruptionRegistry()
                val reopened = newStore(backing, backgroundScope, registry)
                val behavior = GitSyncBehaviorStoreImpl(reopened)
                val collapsed =
                    behavior.run {
                        gitSyncEnabled.first() shouldBe false
                        syncBackendType.first()
                    }

                // Spec: 未知/损坏进入不可用状态，"不能变 NONE/false 并自动取消业务". A destroyed
                // selection reads UNKNOWN so applyRemoteSyncPolicy leaves scheduling untouched.
                collapsed shouldBe SyncBackendType.UNKNOWN.storageValue()
            }
        }

        test("corrupted preferences still quarantine evidence and publish the notice") {
            runTest {
                val backing = Files.createTempFile("corruption-evidence", ".preferences_pb").toFile()
                backing.writeBytes("%%% corrupt %%%".toByteArray())
                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, backgroundScope, registry)

                store.data.first() // triggers the handler

                val quarantined =
                    backing.parentFile!!.listFiles().orEmpty()
                        .filter { it.name.startsWith(backing.name + ".corrupt-") }
                quarantined.size shouldBe 1
                val notice = registry.corruptionNotice.value
                notice shouldNotBe null
                notice!!.quarantinedFileName shouldStartWith "${backing.name}.corrupt-"
            }
        }
    }

    private fun newStore(
        backing: File,
        scope: CoroutineScope,
        registry: PreferencesCorruptionRegistry = PreferencesCorruptionRegistry(),
    ) = PreferenceDataStoreFactory.create(
        scope = scope,
        corruptionHandler =
            lomoPreferencesCorruptionHandler(
                corruptionFile = { backing },
                registry = registry,
            ),
        produceFile = { backing },
    )
}
