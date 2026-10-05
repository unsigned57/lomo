package com.lomo.data.local.datastore

// reaudit-3 adversarial checks for F-1 (audit/12-修复-Android域残留.md): the persisted witness
// must coexist with post-corruption state without misfiring — deliberate user writes always win
// over the sentinel, the durable notice-ack must not outlive the corruption it acknowledged, a
// bulk settings restore must not erase the witness, and a failed ack write re-surfaces the notice
// instead of losing it silently.

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppLockPreference
import com.lomo.domain.model.SyncBackendType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: lomoPreferencesCorruptionHandler + PreferencesCorruptionRegistry across
 *   process generations, plus the witness/readers' coexistence with post-corruption writes.
 * - Owning layer: data/local datastore
 * - Priority tier: P0
 * - Capability: the witness sentinel governs only *absent* keys — a deliberate post-corruption
 *   write ("lock off", "backend none") is a real record and wins. The persisted notice-ack marks
 *   one specific corruption; a second corruption rebuilds the store from the handler's
 *   replacement and must re-derive the notice (the ack flag dies with the corrupt file). A bulk
 *   ordinary-settings restore writes only catalog keys and never erases or fabricates the
 *   witness. A lost ack write is safe: the notice re-surfaces rather than being lost.
 *
 * Scenarios:
 * - Given an acknowledged corruption, when the file corrupts a second time and the store
 *   reopens, then the notice is derivable again — the ack never swallows a new corruption.
 * - Given deliberate user writes after corruption (lock=false, backend=none), when the store
 *   reopens, then the explicit values win over the witness for every reader.
 * - Given a corrupted store, when an ordinary-settings restore transaction writes unrelated
 *   keys, then the witness persists and still-absent keys keep reading as destroyed.
 * - Given an ack whose durable write fails with IOException, when the process restarts, then
 *   the in-memory notice was still cleared and the persisted notice re-surfaces — the witness
 *   itself is untouched.
 *
 * Observable outcomes: AppLockPreference/SyncBackendType reads, persisted notice derivability,
 * and registry.corruptionNotice contents.
 *
 * TDD proof:
 * - These pin post-fix invariants: a merge-not-replace DataStore, an ack that cleared the
 *   witness, or a restore that wiped non-catalog keys would each fail one scenario.
 * Excludes: UI rendering of the notice, inter-process file locking, Android Keystore.
 */
class PreferencesCorruptionWitnessCoexistenceContractTest : DataFunSpec() {
    init {
        test("second corruption after an acknowledged notice re-derives the notice on restart") {
            runTest {
                val backing = corruptBackingFile()
                val firstProcess = processScope(coroutineContext)
                val firstRegistry = PreferencesCorruptionRegistry()
                val firstStore = newStore(backing, firstProcess, firstRegistry)
                firstStore.data.first() // triggers quarantine + witness-bearing rebuild
                firstRegistry.bindWitnessStore(firstStore)
                firstRegistry.acknowledgeCorruptionNotice()

                // The ack is durable in process 1: the persisted payload is suppressed.
                persistedNotice(firstStore) shouldBe null
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                // The file corrupts a second time — the previous ack must not swallow the new
                // corruption's notice on the next process generation.
                backing.writeBytes("%%% corrupted a second time %%%".toByteArray())
                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, processScope(coroutineContext), registry)
                val rebuilt = store.data.first()

                // The rebuilt store carries only the fresh witness: the stale ack is gone, so
                // the notice is derivable again from the persisted state.
                rebuilt[LomoDataStoreKeys.PREFERENCES_CORRUPTION_NOTICE_ACKED] shouldBe null
                persistedNotice(store).shouldNotBeNull()
                registry.republishPersistedNotice(store)
                registry.corruptionNotice.value.shouldNotBeNull()
                registry.corruptionNotice.value!!.quarantinedFileName shouldContain ".corrupt-"
            }
        }

        test("deliberate post-corruption opt-outs (lock=false, backend=none) win over the witness on restart") {
            runTest {
                val backing = corruptBackingFile()
                val firstProcess = processScope(coroutineContext)
                val firstStore = newStore(backing, firstProcess, PreferencesCorruptionRegistry())
                firstStore.data.first()
                // The user re-makes both choices as explicit records on the rebuilt store.
                firstStore.edit {
                    it[LomoDataStoreKeys.APP_LOCK_ENABLED] = false
                    it[LomoDataStoreKeys.SYNC_BACKEND_TYPE] = "none"
                }
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                val store = newStore(backing, processScope(coroutineContext), PreferencesCorruptionRegistry())

                // The witness still exists (it is a fact of the file), but explicit values are
                // never overridden by it.
                store.data.first()[LomoDataStoreKeys.PREFERENCES_CORRUPTION_WITNESS].shouldNotBeNull()
                AppSecurityStoreImpl(store)
                    .readAppLockPreference() shouldBe AppLockPreference.Disabled
                GitSyncBehaviorStoreImpl(store)
                    .syncBackendType
                    .first() shouldBe "none"
            }
        }

        test("an ordinary-settings restore transaction preserves the witness for still-absent keys") {
            runTest {
                val backing = corruptBackingFile()
                val firstProcess = processScope(coroutineContext)
                val firstStore = newStore(backing, firstProcess, PreferencesCorruptionRegistry())
                firstStore.data.first()
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                val store = newStore(backing, processScope(coroutineContext), PreferencesCorruptionRegistry())
                val lomoDataStore = lomoDataStoreOf(store)
                lomoDataStore.restoreOrdinarySettings(
                    LomoOrdinarySettingsRestoreTransaction(
                        catalogValues = emptyMap(),
                        stringValues = mapOf(kotlin.Pair(LomoDataStoreKeys.THEME_MODE, "dark")),
                        nullableStringValues =
                            mapOf(kotlin.Pair(LomoDataStoreKeys.GIT_REMOTE_URL, null)),
                        booleanValues = emptyMap(),
                        intValues = emptyMap(),
                    ),
                )

                // The restore wrote its keys but could not erase or fabricate the witness:
                // the still-absent lock key keeps reading as destroyed, not as opt-out.
                store.data.first()[LomoDataStoreKeys.THEME_MODE] shouldBe "dark"
                AppSecurityStoreImpl(store)
                    .readAppLockPreference() shouldBe AppLockPreference.Unreadable
                GitSyncBehaviorStoreImpl(store)
                    .syncBackendType
                    .first() shouldBe SyncBackendType.UNKNOWN.storageValue()
            }
        }

        test("an acknowledgement whose durable write fails still clears the live notice and re-surfaces after restart") {
            runTest {
                val backing = corruptBackingFile()
                val firstProcess = processScope(coroutineContext)
                val firstRegistry = PreferencesCorruptionRegistry()
                val firstStore = newStore(backing, firstProcess, firstRegistry)
                firstStore.data.first()
                // Bind a store whose every write fails — the ack is best-effort, never fatal.
                firstRegistry.bindWitnessStore(EditFailingDataStore(firstStore))
                firstRegistry.acknowledgeCorruptionNotice()

                firstRegistry.corruptionNotice.value.shouldBeNull()
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()

                // The ack never landed, so the next generation safely re-reports the notice;
                // the witness still guards absent keys regardless.
                val registry = PreferencesCorruptionRegistry()
                val store = newStore(backing, processScope(coroutineContext), registry)
                registry.republishPersistedNotice(store)
                registry.corruptionNotice.value.shouldNotBeNull()
                AppSecurityStoreImpl(store)
                    .readAppLockPreference() shouldBe AppLockPreference.Unreadable
            }
        }
    }

    private suspend fun persistedNotice(store: DataStore<Preferences>) =
        PreferencesCorruptionRegistry.persistedCorruptionNotice(store.data.first())

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

    private fun lomoDataStoreOf(store: DataStore<Preferences>): LomoDataStore {
        val constructor = LomoDataStore::class.java.getDeclaredConstructor(DataStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(store)
    }
}

private class EditFailingDataStore(
    private val delegate: DataStore<Preferences>,
) : DataStore<Preferences> {
    override val data: Flow<Preferences>
        get() = delegate.data

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw IOException("simulated durable-write failure")
}

private fun tempPreferencesFile(): File =
    Files
        .createTempDirectory("lomo-prefs-witness-3")
        .toFile()
        .apply { deleteOnExit() }
        .let { File(it, "lomo.preferences_pb") }
