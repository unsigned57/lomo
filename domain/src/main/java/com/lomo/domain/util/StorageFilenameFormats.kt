package com.lomo.domain.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Single source of truth for memo storage filename date formats.
 *
 * Add a new pattern here and all format-dependent features (settings options, parsing,
 * sidebar/date stats, and storage validation) adapt automatically.
 */
object StorageFilenameFormats {
    const val DEFAULT_PATTERN = "yyyy_MM_dd"

    val supportedPatterns: List<String> =
        listOf(
            DEFAULT_PATTERN,
            "yyyy-MM-dd",
            "yyyy.MM.dd",
            "yyyyMMdd",
            "MM-dd-yyyy",
        )

    private val formattersByPattern: Map<String, DateTimeFormatter> =
        supportedPatterns.associateWith(::buildStrictFormatter)

    private val supportedFormatters: List<DateTimeFormatter> =
        supportedPatterns.map(::buildStrictFormatter)

    fun normalize(pattern: String?): String =
        pattern?.takeIf { supportedPatterns.contains(it) } ?: DEFAULT_PATTERN

    fun formatter(pattern: String): DateTimeFormatter =
        formattersByPattern[normalize(pattern)] ?: formattersByPattern.getValue(DEFAULT_PATTERN)

    fun parseOrNull(raw: String): LocalDate? =
        supportedFormatters.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDate.parse(raw, formatter) }.getOrNull()
        }

    private fun buildStrictFormatter(pattern: String): DateTimeFormatter =
        DateTimeFormatter
            .ofPattern(pattern.replace("yyyy", "uuuu"), Locale.US)
            .withResolverStyle(ResolverStyle.STRICT)
}
