package com.lomo.data.local.entity

import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal object StoredMemoRecovery {
    data class RecoveredStoredMemo(
        val content: String,
        val timestamp: Long,
    )

    fun recoverOrNull(
        rawContent: String,
        storedContent: String,
        storedTimestamp: Long,
        dateKey: String,
    ): RecoveredStoredMemo? {
        val normalizedRawContent = rawContent.trim()
        val lines = rawContent.lines()
        val header = lines.firstOrNull()?.let(StorageTimestampFormats::parseMemoHeaderLine)
        val parsedTime = header?.timePart?.let(StorageTimestampFormats::parseOrNull)
        val recoveredContent =
            header?.let {
                buildString {
                    append(it.contentPart)
                    for (index in 1 until lines.size) {
                        if (isEmpty()) {
                            append(lines[index])
                        } else {
                            append("\n").append(lines[index])
                        }
                    }
                }.trim()
            }
        if (header == null || parsedTime == null || recoveredContent == null) {
            return if (normalizedRawContent.isNotBlank() && normalizedRawContent != storedContent) {
                RecoveredStoredMemo(
                    content = normalizedRawContent,
                    timestamp = storedTimestamp,
                )
            } else {
                null
            }
        }

        val recoveredTimestamp =
            resolveRecoveredTimestamp(
                dateKey = dateKey,
                parsedTime = parsedTime,
                fallbackTimestamp = storedTimestamp,
            )
        val resolvedContent =
            resolveRecoveredContent(
                storedContent = storedContent,
                recoveredContent = recoveredContent,
            )
        val shouldRecoverContent = storedContent != resolvedContent
        val shouldRecoverTimestamp =
            !storedTimestampMatchesRecoveredHeader(
                storedTimestamp = storedTimestamp,
                expectedDate = StorageFilenameFormats.parseOrNull(dateKey),
                expectedTime = parsedTime,
                rawTimePart = header.timePart,
            )

        return RecoveredStoredMemo(
            content = if (shouldRecoverContent) resolvedContent else storedContent,
            timestamp = if (shouldRecoverTimestamp) recoveredTimestamp else storedTimestamp,
        ).takeIf { shouldRecoverContent || shouldRecoverTimestamp }
    }

    private fun resolveRecoveredContent(
        storedContent: String,
        recoveredContent: String,
    ): String =
        if (recoveredContent.isBlank() && storedContent.isNotBlank()) {
            storedContent
        } else {
            recoveredContent
        }

    private fun resolveRecoveredTimestamp(
        dateKey: String,
        parsedTime: LocalTime,
        fallbackTimestamp: Long,
    ): Long =
        StorageFilenameFormats.parseOrNull(dateKey)
            ?.let { parsedDate ->
                LocalDateTime
                    .of(parsedDate, parsedTime)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } ?: fallbackTimestamp

    private fun storedTimestampMatchesRecoveredHeader(
        storedTimestamp: Long,
        expectedDate: LocalDate?,
        expectedTime: LocalTime,
        rawTimePart: String,
    ): Boolean {
        val storedDateTime =
            storedTimestamp
                .takeIf { it > 0L }
                ?.let { timestamp -> Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime() }
                ?: return false
        val matchesDate = expectedDate == null || storedDateTime.toLocalDate() == expectedDate

        return matchesDate &&
            if (rawTimePart.count { it == ':' } >= 2) {
                storedDateTime.hour == expectedTime.hour &&
                    storedDateTime.minute == expectedTime.minute &&
                    storedDateTime.second == expectedTime.second
            } else {
                storedDateTime.hour == expectedTime.hour &&
                    storedDateTime.minute == expectedTime.minute
            }
    }
}
