package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.repository.AppVersionRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppVersionRepositoryImpl
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
    ) : AppVersionRepository {
        override suspend fun getLastAppVersionOnce(): String? = dataStore.getLastAppVersionOnce()

        override suspend fun updateLastAppVersion(version: String?) {
            dataStore.updateLastAppVersion(version)
        }
    }
