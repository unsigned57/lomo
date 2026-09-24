package com.lomo.data.local.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences

/**
 * One-time migration retiring the three independent per-backend enabled flags.
 *
 * `sync_backend_type` is now the sole persisted selection fact; the enabled values are derived
 * views. The migration folds the legacy flags into the selection only when no selection was ever
 * recorded, and only when exactly one flag is `true` — a conflicting legacy state never invents a
 * backend the user did not choose. The retired keys are removed either way.
 */
internal object SyncBackendPreferenceMigration : DataMigration<Preferences> {
    private val RETIRED_FLAG_KEYS =
        listOf(
            LomoDataStoreKeys.GIT_SYNC_ENABLED,
            LomoDataStoreKeys.WEBDAV_SYNC_ENABLED,
            LomoDataStoreKeys.S3_SYNC_ENABLED,
        )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        RETIRED_FLAG_KEYS.any { currentData[it] != null }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        if (prefs[LomoDataStoreKeys.SYNC_BACKEND_TYPE].isNullOrBlank()) {
            val selected =
                listOf(
                    LomoDataStoreKeys.GIT_SYNC_ENABLED to "git",
                    LomoDataStoreKeys.WEBDAV_SYNC_ENABLED to "webdav",
                    LomoDataStoreKeys.S3_SYNC_ENABLED to "s3",
                ).filter { (key, _) -> prefs[key] == true }
            prefs[LomoDataStoreKeys.SYNC_BACKEND_TYPE] =
                if (selected.size == 1) selected.single().second else "none"
        }
        RETIRED_FLAG_KEYS.forEach(prefs::remove)
        return prefs
    }

    override suspend fun cleanUp() = Unit
}
