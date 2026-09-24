package com.lomo.data.local.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.lomo.data.util.PreferenceKeys

/**
 * One-time migration for the explicit Git branch preference.
 *
 * Installs that already persisted a Git remote implicitly synced `main` before the branch was
 * configurable; the migration writes `main` explicitly so the persisted value — never a code
 * default — decides which branch sync targets. Remote URL userinfo is **not** handled here:
 * it requires the Keystore and is owned by the app-layer endpoint security migration.
 */
internal object GitSyncPreferenceMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        !currentData[LomoDataStoreKeys.GIT_REMOTE_URL].isNullOrBlank() &&
            currentData[LomoDataStoreKeys.GIT_BRANCH].isNullOrBlank()

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        if (!prefs[LomoDataStoreKeys.GIT_REMOTE_URL].isNullOrBlank() &&
            prefs[LomoDataStoreKeys.GIT_BRANCH].isNullOrBlank()
        ) {
            prefs[LomoDataStoreKeys.GIT_BRANCH] = PreferenceKeys.Defaults.GIT_BRANCH
        }
        return prefs
    }

    override suspend fun cleanUp() = Unit
}
