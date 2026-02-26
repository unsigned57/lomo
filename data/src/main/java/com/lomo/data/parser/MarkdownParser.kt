package com.lomo.data.parser

import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.util.StorageFilenameFormats
import com.lomo.domain.util.StorageTimestampFormats
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

class MarkdownParser
    @Inject
    constructor(
        private val textProcessor: MemoTextProcessor,
    ) {

        fun parseFile(file: File): List<Memo> {
            if (!file.exists()) return emptyList()
            val filename = file.nameWithoutExtension
            val content = file.readText()
            return parseContent(content, filename, fallbackTimestampMillis = file.lastModified())
        }

        fun parseContent(
            content: String,
            filename: String,
            fallbackTimestampMillis: Long? = null,
        ): List<Memo> {
            val result = mutableListOf<Memo>()
            val lines = content.lines()
            var currentTimestamp = ""
            var currentContentBuilder = StringBuilder()
            var currentRawBuilder = StringBuilder()

            val seenIds = mutableSetOf<String>()
            val timestampCounts = mutableMapOf<String, Int>()

            fun addMemo() {
                if (currentTimestamp.isNotEmpty()) {
                    val fullRaw = currentRawBuilder.toString().trim()
                    val fullContent = currentContentBuilder.toString().trim()

                    // Track occurrence of timestamps to add millisecond offsets
                    // This ensures that items with the same text-time are sorted by file-order (LIFO/FIFO)
                    // rather than random ID hash order.
                    val offset = timestampCounts.getOrDefault(currentTimestamp, 0)
                    timestampCounts[currentTimestamp] = offset + 1

                    // Add offset to timestamp (cap at 999 to stay within same second)
                    val safeOffset = if (offset > 999) 999 else offset
                    val timestampWithOffset =
                        resolveTimestamp(
                            dateStr = filename,
                            timeStr = currentTimestamp,
                            fallbackTimestampMillis = fallbackTimestampMillis,
                        ) + safeOffset
                    val timestampLong = timestampWithOffset

                    // Stable ID: Use filename, timestamp string, AND content hash.
                    // This ensures that even if order changes (e.g. deletion), the ID remains stable.
                    val contentHash =
                        fullContent.trim().hashCode().let {
                            // Use absolute value to avoid negative signs in ID
                            kotlin.math.abs(it).toString(16)
                        }

                    val baseId = "${filename}_${currentTimestamp}_$contentHash"
                    var id = baseId
                    var collisionCount = 1
                    while (seenIds.contains(id)) {
                        id = "${baseId}_$collisionCount"
                        collisionCount++
                    }

                    seenIds.add(id)

                    result.add(
                        Memo(
                            id = id,
                            timestamp = timestampLong,
                            content = fullContent,
                            rawContent = fullRaw,
                            date = filename,
                            tags = extractTags(fullContent),
                            imageUrls = extractImages(fullContent),
                        ),
                    )
                }
            }

            for (line in lines) {
                val header = StorageTimestampFormats.parseMemoHeaderLine(line)
                if (header != null) {
                    addMemo()
                    currentTimestamp = header.timePart
                    currentContentBuilder = StringBuilder(header.contentPart)
                    currentRawBuilder = StringBuilder(line)
                } else {
                    if (currentTimestamp.isNotEmpty()) {
                        if (currentContentBuilder.isEmpty()) {
                            currentContentBuilder.append(line)
                        } else {
                            currentContentBuilder.append("\n").append(line)
                        }
                        currentRawBuilder.append("\n").append(line)
                    }
                }
            }
            addMemo()

            return result
        }

        fun resolveTimestamp(
            dateStr: String,
            timeStr: String,
            fallbackTimestampMillis: Long? = null,
        ): Long =
            run {
                val zoneId = ZoneId.systemDefault()
                val localTime = parseLocalTime(timeStr) ?: LocalTime.MIDNIGHT

                parseLocalDate(dateStr)?.let { parsedDate ->
                    return@run LocalDateTime
                        .of(parsedDate, localTime)
                        .atZone(zoneId)
                        .toInstant()
                        .toEpochMilli()
                }

                // If filename date is unknown, fallback to file metadata date (not "now").
                fallbackTimestampMillis?.let { fallback ->
                    val fallbackDate = Instant.ofEpochMilli(fallback).atZone(zoneId).toLocalDate()
                    return@run LocalDateTime
                        .of(fallbackDate, localTime)
                        .atZone(zoneId)
                        .toInstant()
                        .toEpochMilli()
                }

                0L
            }

        private fun parseLocalDate(dateStr: String): LocalDate? =
            StorageFilenameFormats.parseOrNull(dateStr)

        private fun parseLocalTime(timeStr: String): LocalTime? =
            StorageTimestampFormats.parseOrNull(timeStr)

        private fun extractTags(content: String): List<String> {
            // Delegate to shared utility class to avoid duplication
            return textProcessor.extractTags(content)
        }

        private fun extractImages(content: String): List<String> {
            // Delegate to shared utility class to avoid duplication
            return textProcessor.extractImages(content)
        }
    }
