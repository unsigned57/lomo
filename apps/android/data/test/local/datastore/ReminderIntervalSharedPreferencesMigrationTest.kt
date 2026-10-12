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
 * - Unit under test: ReminderIntervalSharedPreferencesMigration.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: drain the retired lomo_reminder_prefs SharedPreferences island into the main
 *   DataStore once, then delete the legacy file; no compatibility read path remains.
 *
 * Scenarios:
 * - Given a fresh install with no legacy file, when migration is asked, then nothing migrates
 *   and the store keeps its default.
 * - Given a legacy file carrying reminder_interval_millis, when migration runs, then the value
 *   lands in the main store and the legacy file is deleted.
 * - Given a DataStore value plus a leftover legacy file, when migration runs, then the main
 *   value wins and the file is still deleted.
 * - Given a legacy file without the key, when migration runs, then nothing is written but the
 *   file is still deleted.
 *
 * Observable outcomes:
 * - shouldMigrate/migrate results on Preferences; cleanUp invokes the legacy file deletion.
 *
 * TDD proof:
 * - RED: ReminderIntervalSharedPreferencesMigration does not exist before the migration, so
 *   the drain-and-delete contract cannot resolve.
 *
 * Excludes:
 * - DataStore factory wiring, alarm scheduling, and the platform SharedPreferences file.
 */
class ReminderIntervalSharedPreferencesMigrationTest : DataFunSpec() {
    init {
        test("given fresh install without legacy file when migration is asked then nothing runs") {
            runTest {
                var deleted = false
                val migration =
                    ReminderIntervalSharedPreferencesMigration(
                        legacyFilePresent = { false },
                        legacyIntervalMillis = { null },
                        deleteLegacyStore = { deleted = true },
                    )

                migration.shouldMigrate(emptyPreferences()).shouldBeFalse()
                deleted.shouldBeFalse()
            }
        }

        test("given legacy interval when migration runs then value lands in main store and file is deleted") {
            runTest {
                var deleted = false
                val migration =
                    ReminderIntervalSharedPreferencesMigration(
                        legacyFilePresent = { true },
                        legacyIntervalMillis = { 15L * 60_000L },
                        deleteLegacyStore = { deleted = true },
                    )

                migration.shouldMigrate(emptyPreferences()).shouldBeTrue()
                val migrated = migration.migrate(emptyPreferences())

                migrated[LomoDataStoreKeys.REMINDER_INTERVAL_MILLIS] shouldBe 15L * 60_000L
                migration.cleanUp()
                deleted.shouldBeTrue()
            }
        }

        test("given main store value plus leftover legacy file when migration runs then main wins and file is deleted") {
            runTest {
                var deleted = false
                val migration =
                    ReminderIntervalSharedPreferencesMigration(
                        legacyFilePresent = { true },
                        legacyIntervalMillis = { 60_000L },
                        deleteLegacyStore = { deleted = true },
                    )
                val current =
                    emptyPreferences().toMutablePreferences().apply {
                        this[LomoDataStoreKeys.REMINDER_INTERVAL_MILLIS] = 30L * 60_000L
                    }

                migration.shouldMigrate(current).shouldBeTrue()
                val migrated = migration.migrate(current)

                migrated[LomoDataStoreKeys.REMINDER_INTERVAL_MILLIS] shouldBe 30L * 60_000L
                migration.cleanUp()
                deleted.shouldBeTrue()
            }
        }

        test("given legacy file without the key when migration runs then nothing is written but file is deleted") {
            runTest {
                var deleted = false
                val migration =
                    ReminderIntervalSharedPreferencesMigration(
                        legacyFilePresent = { true },
                        legacyIntervalMillis = { null },
                        deleteLegacyStore = { deleted = true },
                    )

                migration.shouldMigrate(emptyPreferences()).shouldBeTrue()
                val migrated = migration.migrate(emptyPreferences())

                migrated[LomoDataStoreKeys.REMINDER_INTERVAL_MILLIS].shouldBeNull()
                migration.cleanUp()
                deleted.shouldBeTrue()
            }
        }
    }
}
