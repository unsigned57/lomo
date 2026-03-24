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
    private const val MIN_RELATIVE_UNIT = 1
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val MILLIS_PER_WEEK = 604_800_000L

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
            diff < MILLIS_PER_MINUTE -> {
                resources.getString(R.string.relative_time_just_now)
            }

            diff < MILLIS_PER_HOUR -> {
                val minutes = (diff / MILLIS_PER_MINUTE).toInt().coerceAtLeast(MIN_RELATIVE_UNIT)
                resources.getQuantityString(R.plurals.relative_time_minutes_ago, minutes, minutes)
            }

            diff < MILLIS_PER_DAY -> {
                val hours = (diff / MILLIS_PER_HOUR).toInt().coerceAtLeast(MIN_RELATIVE_UNIT)
                resources.getQuantityString(R.plurals.relative_time_hours_ago, hours, hours)
            }

            diff < MILLIS_PER_WEEK -> {
                val days = (diff / MILLIS_PER_DAY).toInt().coerceAtLeast(MIN_RELATIVE_UNIT)
                resources.getQuantityString(R.plurals.relative_time_days_ago, days, days)
            }

            else -> {
                formatLocalizedDate(timestamp, resources)
            }
        }
    }

    private fun formatLocalizedDate(
        timestamp: Long,
        resources: Resources,
    ): String =
        runCatching {
            val locales = resources.configuration.locales
            val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]

            DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(defaultZone)
                .format(Instant.ofEpochMilli(timestamp))
        }
            .getOrElse { format(timestamp, "yyyy-MM-dd") }
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
