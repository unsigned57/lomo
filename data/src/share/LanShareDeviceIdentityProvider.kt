package com.lomo.data.share

import com.lomo.data.local.datastore.LomoDataStore

fun interface LanShareDeviceIdentityProvider {
    suspend fun resolveUuid(): String
}

class DataStoreLanShareDeviceIdentityProvider(
    private val dataStore: LomoDataStore,
) : LanShareDeviceIdentityProvider {
    override suspend fun resolveUuid(): String = dataStore.getOrCreateLanShareDeviceUuid()
}
