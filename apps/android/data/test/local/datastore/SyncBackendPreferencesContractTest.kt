package com.lomo.data.local.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

/*
 * Behavior Contract:
 * - Unit under test: sync backend persistence (GitSyncBehaviorStoreImpl + SyncBackendPreferenceMigration)
 * - Owning layer: data/local datastore
 * - Priority tier: P0
 * - Capability: `syncBackendType` is the single persisted backend-selection fact; the three
 *   enabled values are derived views, never independently writable keys, and a controlled
 *   migration retires the legacy flag keys without losing the recorded selection.
 *
 * Scenarios:
 * - Given a stored backend type, when the enabled flows are observed, then exactly the matching
 *   backend reports enabled.
 * - Given a backend write, when preferences are persisted, then only the backend-type key is
 *   written and no per-backend flag keys exist.
 * - Given legacy flag keys without a backend type, when the migration runs, then the backend
 *   type is derived and the flag keys are removed.
 * - Given a corrupted or unknown backend string, when the enabled flows are observed, then
 *   every backend reports disabled without rewriting the stored value.
 *
 * Observable outcomes: derived enabled flows, persisted key sets, migrated preferences.
 *
 * TDD proof:
 * - Fails before the fix because the contract surface under test did not exist.
 * Excludes: scheduler/work execution, UI presentation.
 */
class SyncBackendPreferencesContractTest : DataFunSpec() {
    init {
        test("given a stored backend type when enabled flows are read then only that backend is enabled") {
            runTest {
                val raw = newRawDataStore(backgroundScope)
                raw.edit {
                    it[LomoDataStoreKeys.SYNC_BACKEND_TYPE] = "s3"
                }
                val store = GitSyncBehaviorStoreImpl(raw)
                val webdav = WebDavConnectionStoreImpl(raw)
                val s3 = S3ConnectionStoreImpl(raw)

                store.gitSyncEnabled.first().shouldBeFalse()
                webdav.webDavSyncEnabled.first().shouldBeFalse()
                s3.s3SyncEnabled.first().shouldBeTrue()
            }
        }

        test("given a backend write when preferences are inspected then no flag keys are persisted") {
            runTest {
                val raw = newRawDataStore(backgroundScope)
                val store = GitSyncBehaviorStoreImpl(raw)

                store.setRemoteSyncBackendType("git")

                val prefs = raw.data.first()
                prefs[LomoDataStoreKeys.SYNC_BACKEND_TYPE] shouldBe "git"
                prefs[LomoDataStoreKeys.GIT_SYNC_ENABLED].shouldBeNull()
                prefs[LomoDataStoreKeys.WEBDAV_SYNC_ENABLED].shouldBeNull()
                prefs[LomoDataStoreKeys.S3_SYNC_ENABLED].shouldBeNull()
                store.gitSyncEnabled.first().shouldBeTrue()
            }
        }

        test("given an unknown backend string when enabled flows are read then all report disabled") {
            runTest {
                val raw = newRawDataStore(backgroundScope)
                raw.edit {
                    it[LomoDataStoreKeys.SYNC_BACKEND_TYPE] = "bogus-backend"
                }
                val store = GitSyncBehaviorStoreImpl(raw)
                val webdav = WebDavConnectionStoreImpl(raw)
                val s3 = S3ConnectionStoreImpl(raw)

                store.gitSyncEnabled.first().shouldBeFalse()
                webdav.webDavSyncEnabled.first().shouldBeFalse()
                s3.s3SyncEnabled.first().shouldBeFalse()
                raw.data.first()[LomoDataStoreKeys.SYNC_BACKEND_TYPE] shouldBe "bogus-backend"
            }
        }

        test("given legacy flag keys when migration runs then backend type is derived and flags are removed") {
            runTest {
                val prefs = mutablePreferencesOf(
                    Pair(LomoDataStoreKeys.GIT_SYNC_ENABLED, true),
                    Pair(LomoDataStoreKeys.WEBDAV_SYNC_ENABLED, false),
                    Pair(LomoDataStoreKeys.S3_SYNC_ENABLED, false),
                )

                SyncBackendPreferenceMigration.shouldMigrate(prefs) shouldBe true
                val migrated = SyncBackendPreferenceMigration.migrate(prefs)

                migrated[LomoDataStoreKeys.SYNC_BACKEND_TYPE] shouldBe "git"
                migrated[LomoDataStoreKeys.GIT_SYNC_ENABLED].shouldBeNull()
                migrated[LomoDataStoreKeys.WEBDAV_SYNC_ENABLED].shouldBeNull()
                migrated[LomoDataStoreKeys.S3_SYNC_ENABLED].shouldBeNull()
            }
        }

        test("given a stored backend type when migration runs then flags are still removed") {
            runTest {
                val prefs = mutablePreferencesOf(
                    "webdav",
                    Pair(LomoDataStoreKeys.GIT_SYNC_ENABLED, true),
                    Pair(LomoDataStoreKeys.WEBDAV_SYNC_ENABLED, true),
                )

                val migrated = SyncBackendPreferenceMigration.migrate(prefs)

                migrated[LomoDataStoreKeys.SYNC_BACKEND_TYPE] shouldBe "webdav"
                migrated[LomoDataStoreKeys.GIT_SYNC_ENABLED].shouldBeNull()
                migrated[LomoDataStoreKeys.WEBDAV_SYNC_ENABLED].shouldBeNull()
            }
        }

        test("given conflicting legacy flags when migration runs then no backend is invented") {
            runTest {
                val prefs = mutablePreferencesOf(
                    Pair(LomoDataStoreKeys.GIT_SYNC_ENABLED, true),
                    Pair(LomoDataStoreKeys.WEBDAV_SYNC_ENABLED, true),
                )

                val migrated = SyncBackendPreferenceMigration.migrate(prefs)

                migrated[LomoDataStoreKeys.SYNC_BACKEND_TYPE] shouldBe "none"
                migrated[LomoDataStoreKeys.GIT_SYNC_ENABLED].shouldBeNull()
                migrated[LomoDataStoreKeys.WEBDAV_SYNC_ENABLED].shouldBeNull()
            }
        }
    }
}

private fun mutablePreferencesOf(vararg entries: Pair<Preferences.Key<Boolean>, Boolean>): Preferences =
    androidx.datastore.preferences.core
        .mutablePreferencesOf()
        .apply { entries.forEach { (key, value) -> this[key] = value } }

private fun mutablePreferencesOf(
    backendType: String,
    vararg entries: Pair<Preferences.Key<Boolean>, Boolean>,
): Preferences =
    androidx.datastore.preferences.core
        .mutablePreferencesOf()
        .apply {
            this[LomoDataStoreKeys.SYNC_BACKEND_TYPE] = backendType
            entries.forEach { (key, value) -> this[key] = value }
        }

private fun newRawDataStore(scope: CoroutineScope): androidx.datastore.core.DataStore<Preferences> {
    val backingFile =
        Files.createTempFile("lomo-sync-backend", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
    return PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { backingFile },
    )
}
