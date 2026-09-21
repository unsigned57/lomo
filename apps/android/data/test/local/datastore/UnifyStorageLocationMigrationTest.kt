package com.lomo.data.local.datastore

import androidx.datastore.preferences.core.emptyPreferences
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: UnifyStorageLocationMigration
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: migrate each storage area from dual uri/path keys onto one persisted location.
 *
 * Scenarios:
 * - Given both a legacy URI and a legacy path, when migration runs, then the URI becomes the
 *   unified location and both legacy keys disappear.
 * - Given only a legacy path, when migration runs, then that path becomes the unified location.
 * - Given a unified location plus leftover legacy keys, when migration runs, then the unified
 *   value is kept and leftover keys disappear.
 * - Given only a unified location, when shouldMigrate is asked, then it is false.
 *
 * Observable outcomes:
 * - Preferences after migrate(); shouldMigrate boolean.
 *
 * TDD proof:
 * - Fails before UnifyStorageLocationMigration exists because dual keys remain and there is no
 *   unified location key.
 *
 * Excludes:
 * - DataStore factory wiring, SAF permission grants, workspace engine restore.
 */
class UnifyStorageLocationMigrationTest : DataFunSpec() {
    init {
        test("given both legacy uri and path when migrated then uri wins and legacy keys are removed") {
            runTest {
                val prefs =
                    emptyPreferences().toMutablePreferences().apply {
                        this[LomoDataStoreKeys.ROOT_URI] = "content://tree/root"
                        this[LomoDataStoreKeys.ROOT_DIRECTORY] = "/vault/root"
                        this[LomoDataStoreKeys.IMAGE_URI] = "content://tree/images"
                        this[LomoDataStoreKeys.IMAGE_DIRECTORY] = "/vault/images"
                    }

                UnifyStorageLocationMigration.shouldMigrate(prefs).shouldBeTrue()
                val migrated = UnifyStorageLocationMigration.migrate(prefs)

                migrated[LomoDataStoreKeys.ROOT_LOCATION] shouldBe "content://tree/root"
                migrated[LomoDataStoreKeys.IMAGE_LOCATION] shouldBe "content://tree/images"
                migrated.contains(LomoDataStoreKeys.ROOT_URI).shouldBeFalse()
                migrated.contains(LomoDataStoreKeys.ROOT_DIRECTORY).shouldBeFalse()
                migrated.contains(LomoDataStoreKeys.IMAGE_URI).shouldBeFalse()
                migrated.contains(LomoDataStoreKeys.IMAGE_DIRECTORY).shouldBeFalse()
                UnifyStorageLocationMigration.shouldMigrate(migrated).shouldBeFalse()
            }
        }

        test("given only a legacy path when migrated then the path becomes the unified location") {
            runTest {
                val prefs =
                    emptyPreferences().toMutablePreferences().apply {
                        this[LomoDataStoreKeys.VOICE_DIRECTORY] = "/vault/voice"
                        this[LomoDataStoreKeys.SYNC_INBOX_DIRECTORY] = "/vault/inbox"
                    }

                val migrated = UnifyStorageLocationMigration.migrate(prefs)

                migrated[LomoDataStoreKeys.VOICE_LOCATION] shouldBe "/vault/voice"
                migrated[LomoDataStoreKeys.SYNC_INBOX_LOCATION] shouldBe "/vault/inbox"
                migrated.contains(LomoDataStoreKeys.VOICE_DIRECTORY).shouldBeFalse()
                migrated.contains(LomoDataStoreKeys.SYNC_INBOX_DIRECTORY).shouldBeFalse()
            }
        }

        test("given unified location plus leftover legacy keys when migrated then unified value is kept") {
            runTest {
                val prefs =
                    emptyPreferences().toMutablePreferences().apply {
                        this[LomoDataStoreKeys.ROOT_LOCATION] = "/already/unified"
                        this[LomoDataStoreKeys.ROOT_URI] = "content://stale"
                        this[LomoDataStoreKeys.ROOT_DIRECTORY] = "/stale/path"
                    }

                val migrated = UnifyStorageLocationMigration.migrate(prefs)

                migrated[LomoDataStoreKeys.ROOT_LOCATION] shouldBe "/already/unified"
                migrated.contains(LomoDataStoreKeys.ROOT_URI).shouldBeFalse()
                migrated.contains(LomoDataStoreKeys.ROOT_DIRECTORY).shouldBeFalse()
            }
        }

        test("given only a unified location when shouldMigrate is asked then it is false") {
            runTest {
                val prefs =
                    emptyPreferences().toMutablePreferences().apply {
                        this[LomoDataStoreKeys.ROOT_LOCATION] = "content://tree/root"
                    }

                UnifyStorageLocationMigration.shouldMigrate(prefs).shouldBeFalse()
                prefs[LomoDataStoreKeys.ROOT_URI].shouldBeNull()
            }
        }
    }
}
