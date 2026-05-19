package com.lomo.domain.usecase

import com.lomo.domain.model.ReminderMarker
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

class ParseRemindersUseCase {
    operator fun invoke(content: String): List<ReminderMarker> =
        TOKEN_REGEX.findAll(content).mapNotNull { match -> toMarker(match) }.toList()

    private fun toMarker(match: MatchResult): ReminderMarker? {
        val datePart = match.groupValues[1]
        val timePart = match.groupValues[2]
        val repeatPart = match.groupValues[3]
        val statePart = match.groupValues[4]

        val dueAt =
            try {
                LocalDateTime.parse("$datePart-$timePart", ReminderMarker.TIMESTAMP_FORMAT)
            } catch (_: DateTimeParseException) {
                return null
            }

        val repeatCount = if (repeatPart.isEmpty()) 1 else repeatPart.toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val done = statePart == "done"
        val firedCount =
            when {
                done -> 0
                statePart.isEmpty() -> 0
                else -> statePart.toIntOrNull()?.coerceIn(0, repeatCount) ?: return null
            }
        return ReminderMarker(
            dueAt = dueAt,
            repeatCount = repeatCount,
            firedCount = firedCount,
            done = done,
            tokenRange = match.range,
            raw = match.value,
        )
    }

    private companion object {
        val TOKEN_REGEX =
            Regex("""@(\d{4}-\d{2}-\d{2})-(\d{2}:\d{2})(?:x(\d+))?(?:\.(done|\d+))?""")
    }
}
