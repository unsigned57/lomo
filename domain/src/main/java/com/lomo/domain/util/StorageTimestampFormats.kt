package com.lomo.domain.util

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

    fun normalize(pattern: String?): String =
        pattern?.takeIf { supportedPatterns.contains(it) } ?: DEFAULT_PATTERN

    fun formatter(pattern: String): DateTimeFormatter =
        formattersByPattern[normalize(pattern)] ?: formattersByPattern.getValue(DEFAULT_PATTERN)

    fun parseOrNull(raw: String): LocalTime? =
        parseFormatters.firstNotNullOfOrNull { formatter ->
            runCatching { LocalTime.parse(raw, formatter) }.getOrNull()
        }

    fun parseMemoHeaderLine(line: String): ParsedMemoHeader? {
        val trimmedStart = line.trimStart()
        if (!trimmedStart.startsWith("-")) return null

        val afterDash = trimmedStart.drop(1).trimStart()
        if (afterDash.isEmpty()) return null

        parseFormatters.forEach { formatter ->
            val position = ParsePosition(0)
            val parsed = runCatching { formatter.parse(afterDash, position) }.getOrNull()
            val end = position.index
            if (parsed == null || end <= 0) return@forEach

            // Accept only exact timestamp token boundaries.
            if (end < afterDash.length && !afterDash[end].isWhitespace()) return@forEach

            val timePart = afterDash.substring(0, end)
            val content = afterDash.substring(end).trimStart()
            return ParsedMemoHeader(timePart = timePart, contentPart = content)
        }

        return null
    }

    data class ParsedMemoHeader(
        val timePart: String,
        val contentPart: String,
    )

    private fun buildFormatter(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.US)
}
