package com.lomo.data.repository

internal fun S3RemoteIndexEntry.promoteForRecentActivity(
    now: Long,
    scanEpoch: Long = this.scanEpoch,
): S3RemoteIndexEntry =
    copy(
        lastSeenAt = maxOf(lastSeenAt, now),
        lastVerifiedAt = now,
        scanPriority = recentActivityScanPriority(relativePath, scanPriority),
        dirtySuspect = false,
        missingOnLastScan = false,
        scanEpoch = scanEpoch,
    )

internal fun S3RemoteIndexEntry.promoteForRecentCandidate(
    now: Long,
    scanEpoch: Long = this.scanEpoch,
): S3RemoteIndexEntry =
    copy(
        lastSeenAt = maxOf(lastSeenAt, now),
        scanPriority = recentActivityScanPriority(relativePath, scanPriority),
        dirtySuspect = true,
        missingOnLastScan = false,
        scanEpoch = scanEpoch,
    )
