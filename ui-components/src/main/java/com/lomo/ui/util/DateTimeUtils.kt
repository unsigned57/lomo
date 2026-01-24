package com.lomo.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized date/time formatting utility.
 * Uses a thread-safe cache to avoid repeated DateTimeFormatter creation.
 */
object DateTimeUtils {
    // Use lazy for thread-safe initialization
    private val formatterCache by lazy { ConcurrentHashMap<String, DateTimeFormatter>() }
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
        try {
            val formatter =
                formatterCache.computeIfAbsent(pattern) {
                    DateTimeFormatter.ofPattern(it).withZone(defaultZone)
                }
            formatter.format(Instant.ofEpochMilli(timestamp))
        } catch (e: Exception) {
            // Fallback to ISO format
            formatIso(timestamp)
        }

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
        try {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
                .withZone(defaultZone)
                .format(Instant.ofEpochMilli(timestamp))
        } catch (e: Exception) {
            timestamp.toString()
        }

    /**
     * Format timestamp to relative time (e.g., "2 hours ago").
     * Useful for recent items display.
     */
    fun formatRelative(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000} minutes ago"
            diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
            diff < 604_800_000 -> "${diff / 86_400_000} days ago"
            else -> format(timestamp, "yyyy-MM-dd")
        }
    }
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

/**
 * Format this Long (epoch millis) as relative time (e.g., "2 hours ago").
 *
 * Usage: memo.timestamp.formatRelative()
 */
fun Long.formatRelative(): String = DateTimeUtils.formatRelative(this)
