package com.lomo.data.repository

internal data class PreparedRemoteReconcile(
    val candidatePaths: Set<String> = emptySet(),
    val remoteFiles: Map<String, RemoteS3File> = emptyMap(),
    val observedRemoteEntries: Map<String, S3RemoteIndexEntry>,
    val missingRemotePaths: Set<String>,
    val nextScanCursor: String?,
    val scanEpoch: Long,
    val completedScanCycle: Boolean,
)
