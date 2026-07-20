package com.lomo.data.repository

import com.lomo.data.local.projection.withContentAnalysis
import com.lomo.data.util.MarkdownWorkspaceContentProjector
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentAnalysis
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
    /** Same-parse owner analysis for [memo.content]; reuse for Room projection without re-render. */
    val contentAnalysis: MemoContentAnalysis,
)

/**
 * Produces deterministic save metadata for a memo before file/db persistence.
 * Keeps collision handling and timestamp normalization out of mutation workflow orchestration.
 *
 * Content analysis is produced once from the workspace owner render of free-floating body text.
 */
class MemoSavePlanFactory(
    private val textProcessor: MarkdownWorkspaceContentProjector,
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
            java.time.LocalDateTime
                .of(
                    requireNotNull(StorageFilenameFormats.parseOrNull(dateString)) {
                        "Formatted storage date is invalid: $dateString"
                    },
                    requireNotNull(StorageTimestampFormats.parseOrNull(timeString)) {
                        "Formatted storage time is invalid: $timeString"
                    },
                ).atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        // Same-time ordinal comes from precomputed identity count (DB/workspace), not a second
        // Markdown semantic pass. Blank existing file content means ordinal zero when precomputed
        // is absent (new shard / empty host fixtures).
        val ordinal =
            precomputedSameTimestampCount
                ?: if (existingFileContent.isBlank()) {
                    0
                } else {
                    error(
                        "MemoSavePlanFactory requires precomputedSameTimestampCount when " +
                            "existingFileContent is non-blank; identity ordinal is owned by " +
                            "workspace/DB facts, not line-regex reparse",
                    )
                }
        val canonicalTimestamp =
            memoIdentityPolicy.applyTimestampOffset(
                baseTimestampMillis = baseCanonicalTimestamp,
                occurrenceIndex = ordinal,
            )
        val id = memoIdentityPolicy.buildId(dateString, timeString, ordinal)
        val rawContent = "- $timeString $content"
        val contentAnalysis = textProcessor.analyze(content)
        val memo =
            Memo(
                id = id,
                content = content,
                dateKey = dateString,
                timestamp = canonicalTimestamp,
                rawContent = rawContent,
                localDate = MemoLocalDateResolver.resolve(dateString),
                isDeleted = false,
                geoLocation = geoLocation,
            ).withContentAnalysis(contentAnalysis)
        return MemoSavePlan(
            filename = filename,
            dateKey = dateString,
            timestamp = canonicalTimestamp,
            rawContent = rawContent,
            memo = memo,
            contentAnalysis = contentAnalysis,
        )
    }
}
