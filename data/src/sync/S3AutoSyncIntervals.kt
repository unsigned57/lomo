package com.lomo.data.sync

import java.time.Duration

internal val DEFAULT_S3_AUTO_SYNC_INTERVAL: Duration = Duration.ofHours(S3_AUTO_SYNC_HOURS_1)

internal fun parseS3AutoSyncInterval(interval: String): Duration =
    S3_AUTO_SYNC_INTERVALS[interval] ?: DEFAULT_S3_AUTO_SYNC_INTERVAL

private val S3_AUTO_SYNC_INTERVALS =
    mapOf(
        "30min" to Duration.ofMinutes(S3_AUTO_SYNC_MINUTES_30),
        "1h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_1),
        "6h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_6),
        "12h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_12),
        "24h" to Duration.ofHours(S3_AUTO_SYNC_HOURS_24),
    )

private const val S3_AUTO_SYNC_MINUTES_30 = 30L
private const val S3_AUTO_SYNC_HOURS_1 = 1L
private const val S3_AUTO_SYNC_HOURS_6 = 6L
private const val S3_AUTO_SYNC_HOURS_12 = 12L
private const val S3_AUTO_SYNC_HOURS_24 = 24L
