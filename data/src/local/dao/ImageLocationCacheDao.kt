package com.lomo.data.local.dao

import com.lomo.data.local.entity.ImageLocationCacheEntity

interface ImageLocationCacheDao {
    suspend fun readAll(): List<ImageLocationCacheEntity>

    suspend fun upsertAll(entries: List<ImageLocationCacheEntity>)

    suspend fun clearAll()
}
