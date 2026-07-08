package com.lomo.data.repository
import com.lomo.data.parser.MarkdownParser
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.data.util.MemoTextProcessor
import com.lomo.domain.model.Memo
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.usecase.MemoIdentityPolicy
import java.time.Instant
import java.time.ZoneId
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
constructor(
        private val parser: MarkdownParser,
        private val textProcessor: MemoTextProcessor,
        private val memoIdentityPolicy: MemoIdentityPolicy,
    ) {
        fun create(
            content: String,
            timestamp: Long,
            filenameFormat: String,
            timestampFormat: String,
            existingFileContent: String,
            precomputedSameTimestampCount: Int? = null,
            geoLocation: String? = null,
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
            // The position among same-time blocks drives both the unique id ordinal and the
            // same-second timestamp offset, matching how the parser re-derives them from the file.
            val ordinal =
                precomputedSameTimestampCount
                    ?: countTimestampOccurrences(existingFileContent, timeString)
            val canonicalTimestamp =
                memoIdentityPolicy.applyTimestampOffset(
                    baseTimestampMillis = baseCanonicalTimestamp,
                    occurrenceIndex = ordinal,
                )
            val id = memoIdentityPolicy.buildId(dateString, timeString, ordinal)
            val rawContent = "- $timeString $content"
            val memo =
                Memo(
                    id = id,
                    content = content,
                    dateKey = dateString,
                    timestamp = canonicalTimestamp,
                    rawContent = rawContent,
                    localDate = MemoLocalDateResolver.resolve(dateString),
                    tags = textProcessor.extractTags(content),
                    imageUrls = textProcessor.extractInlineAttachments(content),
                    isDeleted = false,
                    geoLocation = geoLocation,
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
            val pattern = Regex("""^\s*-\s+${Regex.escape(timestamp)}(?:\s|$).*""")
            return fileContent.lineSequence().count(pattern::matches)
        }
    }
