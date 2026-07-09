package com.lomo.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Centralized date/time formatting utility.
 * Uses a thread-safe cache to avoid repeated DateTimeFormatter creation.
 */
object DateTimeUtils {
    // Use lazy for thread-safe initialization
    private val cacheLock = Any()
    private val formatterCache by lazy { mutableMapOf<String, DateTimeFormatter>() }
    private val defaultZone by lazy { ZoneId.systemDefault() }

    /**
     * Format a timestamp to a string using the given pattern.
     * @param timestamp Epoch milliseconds
     * @param pattern DateTimeFormatter pattern (e.g., "yyyy-MM-dd HH:mm")
     * @return Formatted string, or ISO format on error
     */
    fun format(
        timestamp: Long,
        pattern: String,
    ): String =
        runCatching {
            val formatter =
                synchronized(cacheLock) {
                    formatterCache.getOrPut(pattern) {
                        DateTimeFormatter.ofPattern(pattern).withZone(defaultZone)
                    }
                }
            formatter.format(Instant.ofEpochMilli(timestamp))
        }
            .getOrElse { formatIso(timestamp) }

    /**
     * Format a timestamp using date and time patterns.
     * @param timestamp Epoch milliseconds
     * @param datePattern Date pattern (e.g., "yyyy-MM-dd")
     * @param timePattern Time pattern (e.g., "HH:mm")
     * @return Combined formatted string
     */
    fun format(
        timestamp: Long,
        datePattern: String,
        timePattern: String,
    ): String = format(timestamp, "$datePattern $timePattern")

    /**
     * Format timestamp to ISO local date-time format.
     */
    fun formatIso(timestamp: Long): String =
        runCatching {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
                .withZone(defaultZone)
                .format(Instant.ofEpochMilli(timestamp))
        }
            .getOrDefault(timestamp.toString())
}

// ===== Extension Functions for Idiomatic Kotlin =====

/**
 * Format this Long (epoch millis) as a date string.
 * More idiomatic than calling DateTimeUtils.format() directly.
 *
 * Usage: memo.timestamp.formatAsDate("yyyy-MM-dd")
 */
fun Long.formatAsDate(pattern: String): String = DateTimeUtils.format(this, pattern)

/**
 * Format this Long (epoch millis) as a date-time string.
 *
 * Usage: memo.timestamp.formatAsDateTime("yyyy-MM-dd", "HH:mm")
 */
fun Long.formatAsDateTime(
    datePattern: String,
    timePattern: String,
): String = DateTimeUtils.format(this, datePattern, timePattern)

/**
 * Format this Long (epoch millis) as ISO local date-time.
 *
 * Usage: timestamp.formatAsIso()
 */
fun Long.formatAsIso(): String = DateTimeUtils.formatIso(this)
