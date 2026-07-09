package com.lomo.data.repository

internal fun settledScanPriority(
    relativePath: String,
    currentPriority: Int?,
): Int =
    maxOf(
        defaultScanPriority(relativePath),
        (currentPriority ?: defaultScanPriority(relativePath)) - S3_SCAN_PRIORITY_SETTLE_STEP,
    )

internal fun promotedScanPriority(
    relativePath: String,
    currentPriority: Int?,
): Int = maxOf(defaultScanPriority(relativePath) + S3_SCAN_PRIORITY_DIRTY_BONUS, currentPriority ?: 0)

internal fun missingScanPriority(
    relativePath: String,
    currentPriority: Int?,
): Int = maxOf(defaultScanPriority(relativePath) + S3_SCAN_PRIORITY_MISSING_BONUS, currentPriority ?: 0)

internal fun recentActivityScanPriority(
    relativePath: String,
    currentPriority: Int?,
): Int = maxOf(defaultScanPriority(relativePath) + S3_SCAN_PRIORITY_RECENT_ACTIVITY_BONUS, currentPriority ?: 0)

private const val S3_SCAN_PRIORITY_DIRTY_BONUS = 180
private const val S3_SCAN_PRIORITY_MISSING_BONUS = 220
private const val S3_SCAN_PRIORITY_RECENT_ACTIVITY_BONUS = 80
private const val S3_SCAN_PRIORITY_SETTLE_STEP = 20
