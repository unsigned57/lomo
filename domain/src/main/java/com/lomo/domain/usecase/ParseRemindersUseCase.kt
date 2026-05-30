package com.lomo.domain.usecase

import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.Recurrence
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

class ParseRemindersUseCase {
    operator fun invoke(content: String): List<ReminderMarker> =
        TOKEN_REGEX.findAll(content).mapNotNull { match -> toMarker(match) }.toList()

    private fun toMarker(match: MatchResult): ReminderMarker? =
        match.toTokenParts().toMarker(
            tokenRange = match.range,
            raw = match.value,
        )

    private fun ReminderTokenParts.toMarker(
        tokenRange: IntRange,
        raw: String,
    ): ReminderMarker? {
        val dueAt = parseDueAt(datePart = datePart, timePart = timePart)
        val repeatCount = parsePositiveSuffix(rawValue = repeatPart, defaultValue = DEFAULT_REPEAT_COUNT)
        val intervalMinutes = parsePositiveSuffix(rawValue = intervalPart, defaultValue = DEFAULT_INTERVAL_MINUTES)
        val done = statePart == DONE_STATE
        val firedCount = repeatCount?.let { count ->
            parseFiredCount(
                statePart = statePart,
                done = done,
                repeatCount = count,
            )
        }

        return if (dueAt == null || repeatCount == null || intervalMinutes == null || firedCount == null) {
            null
        } else {
            ReminderMarker(
                dueAt = dueAt,
                repeatCount = repeatCount,
                firedCount = firedCount,
                done = done,
                intervalMinutes = intervalMinutes,
                recurrence = Recurrence.fromCode(recurrencePart),
                tokenRange = tokenRange,
                raw = raw,
            )
        }
    }

    private fun MatchResult.toTokenParts(): ReminderTokenParts =
        ReminderTokenParts(
            datePart = groupValues[1],
            timePart = groupValues[2],
            repeatPart = groupValues[3],
            intervalPart = groupValues[4],
            recurrencePart = groupValues[5],
            statePart = groupValues[6],
        )

    private fun parseDueAt(
        datePart: String,
        timePart: String,
    ): LocalDateTime? =
        try {
            LocalDateTime.parse("$datePart-$timePart", ReminderMarker.TIMESTAMP_FORMAT)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun parsePositiveSuffix(
        rawValue: String,
        defaultValue: Int,
    ): Int? =
        if (rawValue.isEmpty()) {
            defaultValue
        } else {
            rawValue.toIntOrNull()?.takeIf { it >= 1 }
        }

    private fun parseFiredCount(
        statePart: String,
        done: Boolean,
        repeatCount: Int,
    ): Int? =
        when {
            done -> 0
            statePart.isEmpty() -> 0
            else -> statePart.toIntOrNull()?.coerceIn(0, repeatCount)
        }

    private data class ReminderTokenParts(
        val datePart: String,
        val timePart: String,
        val repeatPart: String,
        val intervalPart: String,
        val recurrencePart: String,
        val statePart: String,
    )

    private companion object {
        private const val DEFAULT_REPEAT_COUNT = 1
        private const val DEFAULT_INTERVAL_MINUTES = 10
        private const val DONE_STATE = "done"
        private const val TOKEN_PATTERN =
            "@(\\d{4}-\\d{2}-\\d{2})-" +
                "(\\d{2}:\\d{2})" +
                "(?:x(\\d+))?" +
                "(?:i(\\d+))?" +
                "(?:r(d|w))?" +
                "(?:\\.(done|\\d+))?"

        val TOKEN_REGEX =
            Regex(TOKEN_PATTERN)
    }
}
