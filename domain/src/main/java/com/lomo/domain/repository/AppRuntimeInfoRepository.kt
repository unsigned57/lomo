package com.lomo.domain.repository

interface AppRuntimeInfoRepository {
    suspend fun getCurrentVersionName(): String
}
