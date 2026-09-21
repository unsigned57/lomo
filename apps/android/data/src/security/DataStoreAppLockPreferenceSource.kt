package com.lomo.data.security

import com.lomo.data.local.datastore.LomoAppSecurityStore
import com.lomo.domain.model.AppLockPreference
import kotlinx.coroutines.flow.Flow

class DataStoreAppLockPreferenceSource(
    private val securityStore: LomoAppSecurityStore,
) : AppLockPreferenceSource {
    override suspend fun read(): AppLockPreference = securityStore.readAppLockPreference()

    override fun observe(): Flow<AppLockPreference> = securityStore.observeAppLockPreference()
}
