package com.lomo.data.repository

import com.lomo.domain.model.S3SyncDirection

internal const val S3_LARGE_TRANSFER_BYTES = 8L * 1024L * 1024L

internal fun permitsForS3Action(
    action: S3SyncAction,
    localFiles: Map<String, LocalS3File>,
    remoteFiles: Map<String, RemoteS3File>,
    metadataByPath: Map<String, com.lomo.data.local.entity.S3SyncMetadataEntity>,
): Int {
    val size =
        when (action.direction) {
            S3SyncDirection.UPLOAD -> localFiles[action.path]?.size
            S3SyncDirection.DOWNLOAD -> remoteFiles[action.path]?.size ?: metadataByPath[action.path]?.remoteSize
            else -> null
        }
    return if (size != null && size >= S3_LARGE_TRANSFER_BYTES) {
        S3_ACTION_CONCURRENCY
    } else {
        1
    }
}
