package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val S3_LARGE_TRANSFER_BYTES = 8L * 1024L * 1024L
internal const val S3_LARGE_TRANSFER_SMALL_LANE_PERMITS = 3

internal enum class S3ActionLane {
    SMALL,
    LARGE,
}

internal fun laneForS3Action(
    action: S3SyncAction,
    localFiles: Map<String, LocalS3File>,
    remoteFiles: Map<String, RemoteS3File>,
    metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
): S3ActionLane {
    val size =
        when (action.direction) {
            S3SyncDirection.UPLOAD -> localFiles[action.path]?.size
            S3SyncDirection.DOWNLOAD -> remoteFiles[action.path]?.size ?: metadataByPath[action.path]?.remoteSize
            else -> null
        }
    return if (size != null && size >= S3_LARGE_TRANSFER_BYTES) {
        S3ActionLane.LARGE
    } else {
        S3ActionLane.SMALL
    }
}

internal suspend fun <T> withS3ActionLanePermit(
    action: S3SyncAction,
    localFiles: Map<String, LocalS3File>,
    remoteFiles: Map<String, RemoteS3File>,
    metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
    smallLaneLimiter: Semaphore,
    largeLaneLimiter: Semaphore,
    block: suspend () -> T,
): T =
    when (
        laneForS3Action(
            action = action,
            localFiles = localFiles,
            remoteFiles = remoteFiles,
            metadataByPath = metadataByPath,
        )
    ) {
        S3ActionLane.SMALL -> smallLaneLimiter.withPermit { block() }
        S3ActionLane.LARGE -> largeLaneLimiter.withPermit { block() }
    }
