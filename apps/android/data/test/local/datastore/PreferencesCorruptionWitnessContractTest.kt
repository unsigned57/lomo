package com.lomo.data.local.datastore

// reaudit-2 evidence for F-1 (audit/11-再复审-Android域.md): the corruption witness must be a
// durable fact of the rebuilt store, not a process-local flag. A restart rebuilds nothing and
// must still see "this file was replaced after corruption": absent security/sync keys keep
// reading as destroyed (Unreadable/UNKNOWN), never collapse into first-launch defaults, and the
// user-facing notice is reachable again. A store that was never corrupted reports nothing.

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.model.AppLockPreference
import com.lomo.domain.model.SyncBackendType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: lomoPreferencesCorruptionHandler + PreferencesCorruptionRegistry across
 *   process generations (a second DataStore/registry pair on the same file models a restart).
 * - Owning layer: data/local datastore
 * - Priority tier: P0
 * - Capability: the corruption quarantine leaves a durable witness so that after a restart an
 *   absent APP_LOCK_ENABLED still reads Unreadable and an absent SYNC_BACKEND_TYPE still reads
 *   UNKNOWN, and the corruption notice is republished; a healthy file and an acknowledged
 *   notice never produce false corruption facts.
 *
 * Scenarios:
 * - Given a preferences file that corrupted under a previous process, when the store reopens
 *   under a fresh registry, then absent security keys read as destroyed, not defaults.
 * - Given the same restart, when the corruption notice is reloaded, then the quarantine
 *   evidence name and diagnostic survive from the original event.
 * - Given an acknowledged notice, when the store reopens, then the notice stays consumed while
 *   the witness still governs absent keys.
 * - Given a healthy preferences file, when the store opens, then no witness or notice is
 *   reported.
 *
 * Observable outcomes: AppLockPreference/SyncBackendType values, registry.corruptionNotice
 * contents, and durable acknowledgement state.
 *
 * TDD proof:
 * - RED: reopen-time assertions fail while the witness is a process-local StateFlow
 *   (unwitnessed absence reads Disabled/"none" and no notice can resurface).
 * Excludes: UI rendering of the notice, inter-process file locking.
 */
class PreferencesCorruptionWitnessContractTest : DataFunSpec() {
    init {
        test("given a corrupted store when reopened under a fresh process then absent lock key still reads Unreadable") {
            runTest {
                val backing = corruptBackingFile()
                val firstProcess = processScope(coroutineContext)
                val firstStore = newStore(backing, firstProcess, PreferencesCorruptionRegistry())
                firstStore.data.first() // triggers quarantine + rebuild
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                // Restart: new registry, new store, same file — nothing in memory survives.
                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, processScope(coroutineContext), registry)

                AppSecurityStoreImpl(store)
                    .readAppLockPreference() shouldBe AppLockPreference.Unreadable
            }
        }

        test("given a corrupted store when reopened under a fresh process then absent backend still reads UNKNOWN") {
            runTest {
                val backing = corruptBackingFile()
                val firstProcess = processScope(coroutineContext)
                val firstStore = newStore(backing, firstProcess, PreferencesCorruptionRegistry())
                firstStore.data.first()
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, processScope(coroutineContext), registry)

                GitSyncBehaviorStoreImpl(store)
                    .syncBackendType
                    .first() shouldBe SyncBackendType.UNKNOWN.storageValue()
            }
        }

        test("given a corrupted store when reopened under a fresh process then the notice is reachable again") {
            runTest {
                val backing = corruptBackingFile()
                val firstProcess = processScope(coroutineContext)
                val firstStore = newStore(backing, firstProcess, PreferencesCorruptionRegistry())
                firstStore.data.first()
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, processScope(coroutineContext), registry)
                registry.republishPersistedNotice(store)

                val notice = registry.corruptionNotice.value
                notice.shouldNotBeNull()
                notice.quarantinedFileName shouldContain ".corrupt-"
            }
        }

        test("given an acknowledged corruption notice when reopened then it stays consumed while the witness still guards absent keys") {
            runTest {
                val backing = corruptBackingFile()
                val firstProcess = processScope(coroutineContext)
                val firstRegistry = PreferencesCorruptionRegistry()
                val firstStore = newStore(backing, firstProcess, firstRegistry)
                firstStore.data.first()
                firstRegistry.bindWitnessStore(firstStore)
                firstRegistry.acknowledgeCorruptionNotice()
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, processScope(coroutineContext), registry)
                registry.republishPersistedNotice(store)

                registry.corruptionNotice.value.shouldBeNull()
                AppSecurityStoreImpl(store)
                    .readAppLockPreference() shouldBe AppLockPreference.Unreadable
            }
        }

        test("given an explicitly recorded choice after corruption when reopened then the stored value wins over the witness") {
            runTest {
                val backing = corruptBackingFile()
                val firstProcess = processScope(coroutineContext)
                val firstStore = newStore(backing, firstProcess, PreferencesCorruptionRegistry())
                firstStore.data.first()
                // The user re-makes the choice on the rebuilt store; that is a real record,
                // not a destroyed one.
                firstStore.edit { it[LomoDataStoreKeys.APP_LOCK_ENABLED] = true }
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, processScope(coroutineContext), registry)

                AppSecurityStoreImpl(store)
                    .readAppLockPreference() shouldBe AppLockPreference.Enabled
            }
        }

        test("given a healthy preferences file when reopened then no witness or notice is reported") {
            runTest {
                val backing = tempPreferencesFile()
                val firstProcess = processScope(coroutineContext)
                val firstStore = newStore(backing, firstProcess, PreferencesCorruptionRegistry())
                firstStore.edit { it[LomoDataStoreKeys.APP_LOCK_ENABLED] = false }
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, processScope(coroutineContext), registry)
                registry.republishPersistedNotice(store)

                AppSecurityStoreImpl(store)
                    .readAppLockPreference() shouldBe AppLockPreference.Disabled
                GitSyncBehaviorStoreImpl(store)
                    .syncBackendType
                    .first() shouldBe PreferenceKeys.Defaults.SYNC_BACKEND_TYPE
                registry.corruptionNotice.value.shouldBeNull()
            }
        }
    }

    private fun corruptBackingFile(): File =
        tempPreferencesFile().apply { writeBytes("%%% not a serialized preferences file %%%".toByteArray()) }

    private fun processScope(parent: kotlin.coroutines.CoroutineContext): CoroutineScope =
        CoroutineScope(parent + Job())

    private fun newStore(
        backing: File,
        scope: CoroutineScope,
        registry: PreferencesCorruptionRegistry,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            corruptionHandler =
                lomoPreferencesCorruptionHandler(
                    corruptionFile = { backing },
                    registry = registry,
                ),
            produceFile = { backing },
        )
}

private fun tempPreferencesFile(): File =
    Files
        .createTempDirectory("lomo-prefs-witness")
        .toFile()
        .apply { deleteOnExit() }
        .let { File(it, "lomo.preferences_pb") }
