package com.lomo.data.parser

import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.usecase.MemoIdentityPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class MarkdownSourceSpan(
    val startLine: Int,
    val endLine: Int,
)

data class MarkdownMemoBlock(
    val memo: Memo,
    val span: MarkdownSourceSpan,
)

data class MarkdownMemoDocument(
    val blocks: List<MarkdownMemoBlock>,
)

class MarkdownParser
    @Inject
    constructor(
        private val textProcessor: MemoTextProcessor,
        private val memoIdentityPolicy: MemoIdentityPolicy,
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
        ): List<Memo> = parseDocument(content, filename, fallbackTimestampMillis).blocks.map { it.memo }

        fun parseDocument(
            content: String,
            filename: String,
            fallbackTimestampMillis: Long? = null,
        ): MarkdownMemoDocument =
            parseLineSequence(
                lines = content.lines().asSequence(),
                filename = filename,
                fallbackTimestampMillis = fallbackTimestampMillis,
            )

        suspend fun parseDocumentLines(
            lines: Flow<String>,
            filename: String,
            fallbackTimestampMillis: Long? = null,
        ): MarkdownMemoDocument {
            val builder = MarkdownMemoDocumentBuilder(filename, fallbackTimestampMillis)
            lines.collect { line -> builder.accept(line) }
            return builder.build()
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

        private fun parseLocalDate(dateStr: String): LocalDate? = StorageFilenameFormats.parseOrNull(dateStr)

        private fun parseLocalTime(timeStr: String): LocalTime? = StorageTimestampFormats.parseOrNull(timeStr)

        private fun extractTags(content: String): List<String> {
            // Delegate to shared utility class to avoid duplication
            return textProcessor.extractTags(content)
        }

        private fun extractInlineAttachments(content: String): List<String> =
            textProcessor.extractInlineAttachments(content)

        private fun buildPlainMarkdownFallbackMemo(
            content: String,
            filename: String,
            fallbackTimestampMillis: Long?,
        ): Memo? {
            val normalizedContent = content.trim()
            if (normalizedContent.isEmpty()) {
                return null
            }

            val id = memoIdentityPolicy.buildId(filename, PLAIN_MARKDOWN_FALLBACK_TIME, ordinal = 0)
            return Memo(
                id = id,
                timestamp = resolveTimestamp(filename, PLAIN_MARKDOWN_FALLBACK_TIME, fallbackTimestampMillis),
                content = normalizedContent,
                rawContent = normalizedContent,
                dateKey = filename,
                localDate = MemoLocalDateResolver.resolve(filename),
                tags = extractTags(normalizedContent),
                imageUrls = extractInlineAttachments(normalizedContent),
            )
        }

        private fun parseLineSequence(
            lines: Sequence<String>,
            filename: String,
            fallbackTimestampMillis: Long?,
        ): MarkdownMemoDocument {
            val builder = MarkdownMemoDocumentBuilder(filename, fallbackTimestampMillis)
            lines.forEach { line -> builder.accept(line) }
            return builder.build()
        }

        private inner class MarkdownMemoDocumentBuilder(
            private val filename: String,
            private val fallbackTimestampMillis: Long?,
        ) {
            private val result = mutableListOf<MarkdownMemoBlock>()
            private val timestampCounts = mutableMapOf<String, Int>()
            private var lineIndex = 0
            private var sawHeader = false
            private var currentTimestamp = ""
            private var currentStartLine = -1
            private var currentEndLine = -1
            private var currentContentBuilder = StringBuilder()
            private var currentRawBuilder = StringBuilder()
            private var plainFallbackBuilder = StringBuilder()

            fun accept(line: String) {
                val header = StorageTimestampFormats.parseMemoHeaderLine(line)
                if (header != null) {
                    addMemo()
                    sawHeader = true
                    currentTimestamp = header.timePart
                    currentStartLine = lineIndex
                    currentEndLine = lineIndex
                    currentContentBuilder = StringBuilder(header.contentPart)
                    currentRawBuilder = StringBuilder(line)
                } else if (currentTimestamp.isNotEmpty()) {
                    appendBodyLine(line)
                } else if (!sawHeader) {
                    if (plainFallbackBuilder.isNotEmpty()) {
                        plainFallbackBuilder.append('\n')
                    }
                    plainFallbackBuilder.append(line)
                }
                lineIndex++
            }

            fun build(): MarkdownMemoDocument {
                addMemo()
                if (result.isEmpty()) {
                    buildPlainMarkdownFallbackMemo(
                        content = plainFallbackBuilder.toString(),
                        filename = filename,
                        fallbackTimestampMillis = fallbackTimestampMillis,
                    )?.let { memo ->
                        result +=
                            MarkdownMemoBlock(
                                memo = memo,
                                span = MarkdownSourceSpan(startLine = 0, endLine = (lineIndex - 1).coerceAtLeast(0)),
                            )
                    }
                }
                return MarkdownMemoDocument(result.toList())
            }

            private fun appendBodyLine(line: String) {
                if (currentContentBuilder.isEmpty()) {
                    currentContentBuilder.append(line)
                } else {
                    currentContentBuilder.append("\n").append(line)
                }
                currentRawBuilder.append("\n").append(line)
                currentEndLine = lineIndex
            }

            private fun addMemo() {
                if (currentTimestamp.isEmpty()) {
                    return
                }
                val fullRaw = currentRawBuilder.toString().trim()
                val fullContent = currentContentBuilder.toString().trim()

                val ordinal = timestampCounts.getOrDefault(currentTimestamp, 0)
                timestampCounts[currentTimestamp] = ordinal + 1

                val timestampLong =
                    memoIdentityPolicy.applyTimestampOffset(
                        baseTimestampMillis =
                            resolveTimestamp(
                                dateStr = filename,
                                timeStr = currentTimestamp,
                                fallbackTimestampMillis = fallbackTimestampMillis,
                            ),
                        occurrenceIndex = ordinal,
                    )

                val id = memoIdentityPolicy.buildId(filename, currentTimestamp, ordinal)

                result +=
                    MarkdownMemoBlock(
                        memo =
                            Memo(
                                id = id,
                                timestamp = timestampLong,
                                content = fullContent,
                                rawContent = fullRaw,
                                dateKey = filename,
                                localDate = MemoLocalDateResolver.resolve(filename),
                                tags = extractTags(fullContent),
                                imageUrls = extractInlineAttachments(fullContent),
                            ),
                        span = MarkdownSourceSpan(startLine = currentStartLine, endLine = currentEndLine),
                    )
                currentTimestamp = ""
            }
        }
    }

private const val PLAIN_MARKDOWN_FALLBACK_TIME = "00:00:00"
