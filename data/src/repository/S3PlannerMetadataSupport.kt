package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.entity.S3SyncMetadataEntity

internal suspend fun S3SyncMetadataDao.readAllPlannerMetadataByPath(): Map<String, S3SyncMetadataEntity> =
    getAllPlannerMetadataSnapshots()
        .associate { snapshot ->
            snapshot.relativePath to snapshot.toEntity()
        }

private fun S3SyncPlannerMetadataSnapshot.toEntity(): S3SyncMetadataEntity =
    S3SyncMetadataEntity(
        relativePath = relativePath,
        remotePath = remotePath,
        etag = etag,
        remoteLastModified = remoteLastModified,
        localLastModified = localLastModified,
        localSize = localSize,
        remoteSize = remoteSize,
        localFingerprint = localFingerprint,
        lastSyncedAt = lastSyncedAt,
        lastResolvedDirection = lastResolvedDirection,
        lastResolvedReason = lastResolvedReason,
    )
