package com.lomo.data.local.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.lomo.data.webdav.WebDavCredentialStore
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * One-time drain of the retired plaintext `webdav_username` copy out of the main preferences
 * file into the credential store.
 *
 * Runs as a real DataStore migration, so the plaintext is removed in the same atomic
 * transaction that the first post-upgrade read observes — no read can ever see both copies.
 * The legacy key is only removed after the credential store holds (or already holds) a
 * username: a credential-store failure keeps the legacy value so the next launch retries,
 * and the lazy drains in the sync settings paths still work as a fallback.
 *
 * A pre-existing credential value wins over the stale plaintext copy — the plaintext only
 * ever fills an empty slot.
 */
internal class LegacyWebDavUsernameDrainMigration(
    private val credentialStoreFactory: () -> WebDavCredentialStore,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        !currentData[LomoDataStoreKeys.WEBDAV_USERNAME].isNullOrBlank()

    override suspend fun migrate(currentData: Preferences): Preferences {
        val legacyUsername = currentData[LomoDataStoreKeys.WEBDAV_USERNAME]?.trim().orEmpty()
        val credentialStore = credentialStoreFactory()
        try {
            if (credentialStore.getUsername().isNullOrBlank()) {
                credentialStore.setUsername(legacyUsername)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep the legacy key in place when the
            // credential write fails so the next launch (or the lazy drain) retries instead
            // of losing the user's stored username.
            Timber.w(error, "Legacy webdav_username drain deferred: credential write failed")
            return currentData
        }
        val prefs = currentData.toMutablePreferences()
        prefs.remove(LomoDataStoreKeys.WEBDAV_USERNAME)
        return prefs
    }

    override suspend fun cleanUp() = Unit
}
