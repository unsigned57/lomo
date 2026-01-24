package com.lomo.data.parser

import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

class MarkdownParser(
    private val textProcessor: MemoTextProcessor = MemoTextProcessor(),
) {
    // Use lazy delegation for expensive Pattern compilation
    // Pattern is only compiled when first accessed, saving initialization cost
    // Regex: - HH:mm:ss [Content]
    // Flexible:
    // ^\s*-\s+ : Allow leading whitespace, dash, and one or more spaces
    // (\d{1,2}:\d{2}(?::\d{2})?) : Capture HH:mm or HH:mm:ss
    // (?:\s+(.*))?$ : Optional space and remaining content
    private val timePattern by lazy {
        Pattern.compile("^\\s*-\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)(?:\\s+(.*))?$")
    }

    fun parseFile(file: File): List<Memo> {
        if (!file.exists()) return emptyList()
        val filename = file.nameWithoutExtension
        val content = file.readText()
        return parseContent(content, filename)
    }

    fun parseContent(
        content: String,
        filename: String,
    ): List<Memo> {
        val result = mutableListOf<Memo>()
        val lines = content.lines()
        var currentTimestamp = ""
        var currentContentBuilder = StringBuilder()
        var currentRawBuilder = StringBuilder()

        val seenIds = mutableSetOf<String>()

        fun addMemo() {
            if (currentTimestamp.isNotEmpty()) {
                val fullRaw = currentRawBuilder.toString().trim()
                val fullContent = currentContentBuilder.toString().trim()
                var id = "${filename}_$currentTimestamp"

                // Ensure ID uniqueness to prevent LazyColumn crashes and UI duplicates
                var collisionCount = 0
                val originalId = id
                while (seenIds.contains(id)) {
                    collisionCount++
                    id = "${originalId}_$collisionCount"
                }
                seenIds.add(id)

                val timestampLong = parseTimestamp(filename, currentTimestamp)

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
            val matcher = timePattern.matcher(line)
            if (matcher.find()) {
                addMemo()
                currentTimestamp = matcher.group(1) ?: ""
                val contentPart = matcher.group(2) ?: ""
                currentContentBuilder = StringBuilder(contentPart)
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

    private fun parseTimestamp(
        dateStr: String,
        timeStr: String,
    ): Long =
        try {
            // Normalize timeStr to ensure HH:mm:ss
            val timeParts = timeStr.split(":")
            val normalizedTime =
                when (timeParts.size) {
                    2 -> "$timeStr:00"
                    3 -> timeStr
                    else -> "00:00:00"
                }

            val fullDateTimeString = "$dateStr $normalizedTime"
            val formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd HH:mm:ss")
            val localDateTime = LocalDateTime.parse(fullDateTimeString, formatter)
            localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }

    private fun extractTags(content: String): List<String> {
        // Delegate to shared utility class to avoid duplication
        return textProcessor.extractTags(content)
    }

    private fun extractImages(content: String): List<String> {
        // Delegate to shared utility class to avoid duplication
        return textProcessor.extractImages(content)
    }
}
