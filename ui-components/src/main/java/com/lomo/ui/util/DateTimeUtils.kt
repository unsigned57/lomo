package com.lomo.ui.util

import android.content.Context
import android.content.res.Resources
import com.lomo.ui.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
        try {
            val formatter =
                synchronized(cacheLock) {
                    formatterCache.getOrPut(pattern) {
                        DateTimeFormatter.ofPattern(pattern).withZone(defaultZone)
                    }
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
     * Format timestamp to localized relative time (e.g., "2 hours ago").
     * Relative text is always resolved from Android resources.
     */
    fun formatRelative(
        timestamp: Long,
        context: Context,
    ): String = formatRelative(timestamp, context.resources)

    /**
     * Format timestamp to localized relative time (e.g., "2 hours ago").
     * Relative text is always resolved from Android resources.
     */
    fun formatRelative(
        timestamp: Long,
        resources: Resources,
    ): String {
        val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)

        return when {
            diff < 60_000L -> resources.getString(R.string.relative_time_just_now)
            diff < 3_600_000L -> {
                val minutes = (diff / 60_000L).toInt().coerceAtLeast(1)
                resources.getQuantityString(R.plurals.relative_time_minutes_ago, minutes, minutes)
            }

            diff < 86_400_000L -> {
                val hours = (diff / 3_600_000L).toInt().coerceAtLeast(1)
                resources.getQuantityString(R.plurals.relative_time_hours_ago, hours, hours)
            }

            diff < 604_800_000L -> {
                val days = (diff / 86_400_000L).toInt().coerceAtLeast(1)
                resources.getQuantityString(R.plurals.relative_time_days_ago, days, days)
            }

            else -> formatLocalizedDate(timestamp, resources)
        }
    }

    private fun formatLocalizedDate(
        timestamp: Long,
        resources: Resources,
    ): String =
        try {
            val locales = resources.configuration.locales
            val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]

            DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(defaultZone)
                .format(Instant.ofEpochMilli(timestamp))
        } catch (e: Exception) {
            format(timestamp, "yyyy-MM-dd")
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
 * Format this Long (epoch millis) as localized relative time (e.g., "2 hours ago").
 *
 * Usage: memo.timestamp.formatRelative(context)
 */
fun Long.formatRelative(context: Context): String = DateTimeUtils.formatRelative(this, context)

/**
 * Format this Long (epoch millis) as localized relative time (e.g., "2 hours ago").
 *
 * Usage: memo.timestamp.formatRelative(resources)
 */
fun Long.formatRelative(resources: Resources): String = DateTimeUtils.formatRelative(this, resources)
