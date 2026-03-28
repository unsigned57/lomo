package com.lomo.domain.model

import java.text.ParsePosition
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Single source of truth for memo storage timestamp formats.
 *
 * Add a new pattern here and formatting + parsing paths will adapt automatically.
 */
object StorageTimestampFormats {
    const val DEFAULT_PATTERN = "HH:mm:ss"

    val supportedPatterns: List<String> =
        listOf(
            DEFAULT_PATTERN,
            "HH:mm",
        )

    private val formattersByPattern: Map<String, DateTimeFormatter> =
        supportedPatterns.associateWith(::buildFormatter)

    private val parseFormatters: List<DateTimeFormatter> =
        supportedPatterns
            .asSequence()
            .flatMap { pattern ->
                sequenceOf(
                    pattern,
                    pattern.replace("HH", "H"),
                    pattern.replace("hh", "h"),
                )
            }.distinct()
            .map(::buildFormatter)
            .toList()

    fun normalize(pattern: String?): String = pattern?.takeIf { supportedPatterns.contains(it) } ?: DEFAULT_PATTERN

    fun formatter(pattern: String): DateTimeFormatter =
        formattersByPattern[normalize(pattern)] ?: formattersByPattern.getValue(DEFAULT_PATTERN)

    fun parseOrNull(raw: String): LocalTime? =
        parseFormatters.firstNotNullOfOrNull { formatter ->
            runCatching { LocalTime.parse(raw, formatter) }.getOrNull()
        }

    fun parseMemoHeaderLine(line: String): ParsedMemoHeader? {
        val afterDash =
            line
                .trimStart(::isIgnorableHeaderSeparator)
                .takeIf { trimmed -> trimmed.startsWith("-") }
                ?.drop(1)
                ?.trimStart(::isIgnorableHeaderSeparator)
                ?.takeIf(String::isNotEmpty)
                ?: return null

        return parseFormatters
            .asSequence()
            .mapNotNull { formatter -> parseHeader(formatter, afterDash) }
            .firstOrNull()
    }

    data class ParsedMemoHeader(
        val timePart: String,
        val contentPart: String,
    )

    private fun parseHeader(
        formatter: DateTimeFormatter,
        afterDash: String,
    ): ParsedMemoHeader? {
        val position = ParsePosition(0)
        val parsed = runCatching { formatter.parse(afterDash, position) }.getOrNull()
        val end = position.index
        if (parsed == null || end <= 0) {
            return null
        }

        val hasExactBoundary = end >= afterDash.length || isIgnorableHeaderSeparator(afterDash[end])
        return if (hasExactBoundary) {
            ParsedMemoHeader(
                timePart = afterDash.substring(0, end),
                contentPart = afterDash.substring(end).trimStart(::isIgnorableHeaderSeparator),
            )
        } else {
            null
        }
    }

    private fun buildFormatter(pattern: String): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.US)

    private fun isIgnorableHeaderSeparator(char: Char): Boolean =
        char.isWhitespace() ||
            char == UTF8_BOM ||
            char == ZERO_WIDTH_SPACE
}

private const val UTF8_BOM = '\uFEFF'
private const val ZERO_WIDTH_SPACE = '\u200B'
