package com.lomo.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * One-shot drain of the retired `lomo_reminder_prefs` SharedPreferences island into the main
 * DataStore. The legacy file carried a single long (`reminder_interval_millis`); after the
 * value lands the file is deleted and no compatibility read path remains — the store's
 * [LomoReminderPreferencesStore] is the only home for the snooze interval.
 *
 * A present-but-empty legacy file is still deleted: the island was created lazily, so its mere
 * existence is the only signal the drain needs. When both stores hold a value the main
 * DataStore wins — it is already the authoritative fact.
 */
internal class ReminderIntervalSharedPreferencesMigration(
    private val legacyFilePresent: () -> Boolean,
    private val legacyIntervalMillis: () -> Long?,
    private val deleteLegacyStore: () -> Unit,
) : DataMigration<Preferences> {
    constructor(context: Context) : this(
        legacyFilePresent = {
            File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_NAME.xml").exists()
        },
        legacyIntervalMillis = {
            context
                .getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .let { prefs ->
                    if (prefs.contains(LEGACY_KEY_INTERVAL_MILLIS)) {
                        prefs.getLong(LEGACY_KEY_INTERVAL_MILLIS, 0L)
                    } else {
                        null
                    }
                }
        },
        deleteLegacyStore = { context.deleteSharedPreferences(LEGACY_PREFS_NAME) },
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacyFilePresent()

    override suspend fun migrate(currentData: Preferences): Preferences {
        val legacyValue = legacyIntervalMillis()
        if (legacyValue == null || currentData.contains(LomoDataStoreKeys.REMINDER_INTERVAL_MILLIS)) {
            return currentData
        }
        return currentData.toMutablePreferences().apply {
            this[LomoDataStoreKeys.REMINDER_INTERVAL_MILLIS] = legacyValue
        }
    }

    override suspend fun cleanUp() {
        deleteLegacyStore()
    }

    private companion object {
        const val LEGACY_PREFS_NAME = "lomo_reminder_prefs"
        const val LEGACY_KEY_INTERVAL_MILLIS = "reminder_interval_millis"
    }
}
