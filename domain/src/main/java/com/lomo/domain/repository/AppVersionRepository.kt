package com.lomo.domain.repository

interface AppVersionRepository {
    suspend fun getLastAppVersionOnce(): String?

    suspend fun updateLastAppVersion(version: String?)
}

