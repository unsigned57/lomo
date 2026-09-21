package com.lomo.data.local.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.lomo.data.source.isContentStorageUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object UnifyStorageLocationMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        STORAGE_LOCATION_LEGACY_PAIRS.any { pair ->
            currentData.contains(pair.uriKey) || currentData.contains(pair.pathKey)
        }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        STORAGE_LOCATION_LEGACY_PAIRS.forEach { pair ->
            if (prefs[pair.unifiedKey] == null) {
                val migrated = prefs[pair.uriKey] ?: prefs[pair.pathKey]
                if (migrated != null) {
                    prefs[pair.unifiedKey] = migrated
                }
            }
            prefs.remove(pair.uriKey)
            prefs.remove(pair.pathKey)
        }
        return prefs
    }

    override suspend fun cleanUp() = Unit
}

internal data class StorageLocationKeyPair(
    val uriKey: Preferences.Key<String>,
    val pathKey: Preferences.Key<String>,
    val unifiedKey: Preferences.Key<String>,
)

internal val STORAGE_LOCATION_LEGACY_PAIRS: List<StorageLocationKeyPair> =
    listOf(
        StorageLocationKeyPair(
            uriKey = LomoDataStoreKeys.ROOT_URI,
            pathKey = LomoDataStoreKeys.ROOT_DIRECTORY,
            unifiedKey = LomoDataStoreKeys.ROOT_LOCATION,
        ),
        StorageLocationKeyPair(
            uriKey = LomoDataStoreKeys.IMAGE_URI,
            pathKey = LomoDataStoreKeys.IMAGE_DIRECTORY,
            unifiedKey = LomoDataStoreKeys.IMAGE_LOCATION,
        ),
        StorageLocationKeyPair(
            uriKey = LomoDataStoreKeys.VOICE_URI,
            pathKey = LomoDataStoreKeys.VOICE_DIRECTORY,
            unifiedKey = LomoDataStoreKeys.VOICE_LOCATION,
        ),
        StorageLocationKeyPair(
            uriKey = LomoDataStoreKeys.SYNC_INBOX_URI,
            pathKey = LomoDataStoreKeys.SYNC_INBOX_DIRECTORY,
            unifiedKey = LomoDataStoreKeys.SYNC_INBOX_LOCATION,
        ),
    )

internal fun DataStore<Preferences>.uriLocationFlow(
    key: Preferences.Key<String>,
    flowName: String,
): Flow<String?> =
    nullableStringFlow(key, flowName).map { value -> value?.takeIf(::isContentStorageUri) }

internal fun DataStore<Preferences>.pathLocationFlow(
    key: Preferences.Key<String>,
    flowName: String,
): Flow<String?> =
    nullableStringFlow(key, flowName).map { value -> value?.takeUnless(::isContentStorageUri) }

internal suspend fun DataStore<Preferences>.updateUnifiedLocation(
    key: Preferences.Key<String>,
    value: String?,
    asUri: Boolean,
) {
    editPreferences {
        val current = this[key]
        when {
            value != null -> this[key] = value
            current != null && isContentStorageUri(current) == asUri -> remove(key)
        }
    }
}
