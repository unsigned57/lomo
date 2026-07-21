package com.lomo.data.local.dao

import com.lomo.data.local.entity.S3SyncProtocolStateEntity

interface S3SyncProtocolStateDao {
    suspend fun getById(
        workspaceGeneration: String,
        id: Int = S3SyncProtocolStateEntity.SINGLETON_ID,
    ): S3SyncProtocolStateEntity?

    suspend fun upsert(entity: S3SyncProtocolStateEntity)

    suspend fun clearAll(workspaceGeneration: String)
}
