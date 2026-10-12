package com.lomo.domain.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Kotlin projection of the Rust `DateFormat` registry (`lomo-application::calendar`).
 *
 * The token's semantics are owned by Rust `DateFormat::from_token`/`pattern()`; this table only
 * mirrors the supported set for settings options and offline filename parsing. It never rewrites
 * a token: writers and readers transport the configured value verbatim and unknown tokens fail
 * closed at the Rust session boundary instead of being healed here.
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

    fun formatter(pattern: String): DateTimeFormatter =
        formattersByPattern[pattern]
            ?: throw IllegalArgumentException("Unsupported storage filename format: $pattern")

    fun parseOrNull(raw: String): LocalDate? =
        supportedFormatters.firstNotNullOfOrNull { formatter ->
            // behavior-contract: silent-result-ok: format mismatch is expected; loop tries next
            runCatching { LocalDate.parse(raw, formatter) }.getOrNull()
        }

    fun parseFilenameOrNull(filename: String): LocalDate? {
        if (!filename.endsWith(MARKDOWN_EXTENSION)) return null
        return parseOrNull(filename.removeSuffix(MARKDOWN_EXTENSION))
    }

    private fun buildStrictFormatter(pattern: String): DateTimeFormatter =
        DateTimeFormatter
            .ofPattern(pattern.replace("yyyy", "uuuu"), Locale.US)
            .withResolverStyle(ResolverStyle.STRICT)
}

private const val MARKDOWN_EXTENSION = ".md"
