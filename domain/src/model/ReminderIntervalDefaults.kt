package com.lomo.domain.model

object ReminderIntervalDefaults {
    val SUPPORTED_MILLIS: List<Long> =
        listOf(
            60_000L,
            3 * 60_000L,
            5 * 60_000L,
            15 * 60_000L,
            30 * 60_000L,
            60 * 60_000L,
        )
    const val DEFAULT_MILLIS: Long = 5L * 60_000L
}
