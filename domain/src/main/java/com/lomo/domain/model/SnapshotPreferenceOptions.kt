package com.lomo.domain.model

private const val SNAPSHOT_COUNT_OPTION_10 = 10
private const val SNAPSHOT_COUNT_OPTION_20 = 20
private const val SNAPSHOT_COUNT_OPTION_30 = 30
private const val SNAPSHOT_COUNT_OPTION_50 = 50
private const val SNAPSHOT_COUNT_OPTION_100 = 100
private const val SNAPSHOT_DAY_OPTION_7 = 7
private const val SNAPSHOT_DAY_OPTION_14 = 14
private const val SNAPSHOT_DAY_OPTION_30 = 30
private const val SNAPSHOT_DAY_OPTION_90 = 90
private const val SNAPSHOT_DAY_OPTION_180 = 180

object SnapshotPreferenceOptions {
    val RETENTION_COUNT_OPTIONS =
        listOf(
            SNAPSHOT_COUNT_OPTION_10,
            SNAPSHOT_COUNT_OPTION_20,
            SNAPSHOT_COUNT_OPTION_30,
            SNAPSHOT_COUNT_OPTION_50,
            SNAPSHOT_COUNT_OPTION_100,
        )

    val RETENTION_DAY_OPTIONS =
        listOf(
            SNAPSHOT_DAY_OPTION_7,
            SNAPSHOT_DAY_OPTION_14,
            SNAPSHOT_DAY_OPTION_30,
            SNAPSHOT_DAY_OPTION_90,
            SNAPSHOT_DAY_OPTION_180,
        )
}
