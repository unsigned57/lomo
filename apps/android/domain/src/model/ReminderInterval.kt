package com.lomo.domain.model

private const val ONE_MINUTE_MILLIS: Long = 60_000L
private const val THREE_MINUTES_MILLIS: Long = 3 * ONE_MINUTE_MILLIS
private const val FIVE_MINUTES_MILLIS: Long = 5 * ONE_MINUTE_MILLIS
private const val FIFTEEN_MINUTES_MILLIS: Long = 15 * ONE_MINUTE_MILLIS
private const val THIRTY_MINUTES_MILLIS: Long = 30 * ONE_MINUTE_MILLIS
private const val SIXTY_MINUTES_MILLIS: Long = 60 * ONE_MINUTE_MILLIS

/**
 * Snooze interval preference vocabulary — the domain projection of the owner
 * `SnoozeInterval`/`ReminderSnoozeInterval` option set.
 *
 * A snooze duration is never an arbitrary millisecond count: values outside this enum are
 * unrepresentable, and the native boundary passes the variant (never the millis). [millis] is the
 * owner-projected duration carried for durable-preference storage only; the data layer maps
 * variants to wire enums verbatim.
 */
enum class ReminderInterval(val millis: Long) {
    ONE_MINUTE(ONE_MINUTE_MILLIS),
    THREE_MINUTES(THREE_MINUTES_MILLIS),
    FIVE_MINUTES(FIVE_MINUTES_MILLIS),
    FIFTEEN_MINUTES(FIFTEEN_MINUTES_MILLIS),
    THIRTY_MINUTES(THIRTY_MINUTES_MILLIS),
    SIXTY_MINUTES(SIXTY_MINUTES_MILLIS),
    ;

    companion object {
        /** Interval applied when no stored preference exists — owner `SnoozeInterval::DEFAULT`. */
        const val DEFAULT_MILLIS: Long = FIVE_MINUTES_MILLIS

        val DEFAULT: ReminderInterval = FIVE_MINUTES

        /**
         * Decodes a stored millisecond preference into the vocabulary. A value outside the
         * option set is corrupt durable state and fails explicitly.
         */
        fun fromMillis(millis: Long): ReminderInterval = entries.first { it.millis == millis }
    }
}
