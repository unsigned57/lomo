package com.lomo.data.repository

import com.lomo.data.parser.MarkdownParser
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.util.StorageFilenameFormats
import com.lomo.domain.util.StorageTimestampFormats
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class MemoSavePlan(
    val filename: String,
    val dateKey: String,
    val timestamp: Long,
    val rawContent: String,
    val memo: Memo,
)

/**
 * Produces deterministic save metadata for a memo before file/db persistence.
 * Keeps collision handling and timestamp normalization out of mutation workflow orchestration.
 */
class MemoSavePlanFactory
    @Inject
    constructor(
        private val parser: MarkdownParser,
        private val textProcessor: MemoTextProcessor,
    ) {
        fun create(
            content: String,
            timestamp: Long,
            filenameFormat: String,
            timestampFormat: String,
            existingFileContent: String,
            precomputedSameTimestampCount: Int? = null,
            precomputedCollisionCount: Int? = null,
        ): MemoSavePlan {
            val instant = Instant.ofEpochMilli(timestamp)
            val zoneId = ZoneId.systemDefault()
            val filename =
                StorageFilenameFormats
                    .formatter(filenameFormat)
                    .withZone(zoneId)
                    .format(instant) + ".md"
            val timeString =
                StorageTimestampFormats
                    .formatter(timestampFormat)
                    .withZone(zoneId)
                    .format(instant)
            val dateString = filename.removeSuffix(".md")

            val baseCanonicalTimestamp =
                parser.resolveTimestamp(
                    dateStr = dateString,
                    timeStr = timeString,
                    fallbackTimestampMillis = timestamp,
                )
            val sameTimestampCount = precomputedSameTimestampCount ?: countTimestampOccurrences(existingFileContent, timeString)
            val safeOffset = if (sameTimestampCount > 999) 999 else sameTimestampCount
            val canonicalTimestamp = baseCanonicalTimestamp + safeOffset

            val contentHash =
                content
                    .trim()
                    .hashCode()
                    .let {
                        kotlin.math.abs(it).toString(16)
                    }
            val baseId = "${dateString}_${timeString}_$contentHash"
            val collisionIndex = precomputedCollisionCount ?: countBaseIdCollisionsInFile(
                fileContent = existingFileContent,
                dateString = dateString,
                fallbackTimestampMillis = timestamp,
                baseId = baseId,
            )
            val optimisticId = if (collisionIndex == 0) baseId else "${baseId}_$collisionIndex"
            val rawContent = "- $timeString $content"

            val memo =
                Memo(
                    id = optimisticId,
                    content = content,
                    dateKey = dateString,
                    timestamp = canonicalTimestamp,
                    rawContent = rawContent,
                    tags = textProcessor.extractTags(content),
                    imageUrls = textProcessor.extractImages(content),
                    isDeleted = false,
                )

            return MemoSavePlan(
                filename = filename,
                dateKey = dateString,
                timestamp = canonicalTimestamp,
                rawContent = rawContent,
                memo = memo,
            )
        }

        private fun countTimestampOccurrences(
            fileContent: String,
            timestamp: String,
        ): Int {
            if (fileContent.isBlank()) return 0
            val pattern = Regex("^\\s*-\\s+${Regex.escape(timestamp)}(?:\\s|$).*")
            return fileContent.lineSequence().count(pattern::matches)
        }

        private fun countBaseIdCollisionsInFile(
            fileContent: String,
            dateString: String,
            fallbackTimestampMillis: Long,
            baseId: String,
        ): Int {
            if (fileContent.isBlank()) return 0

            val collisionPrefix = "${baseId}_"
            return parser
                .parseContent(
                    content = fileContent,
                    filename = dateString,
                    fallbackTimestampMillis = fallbackTimestampMillis,
                ).count { memo ->
                    memo.id == baseId || memo.id.startsWith(collisionPrefix)
                }
        }
    }
