package com.lomo.data.repository

import com.lomo.data.local.entity.StoredMemoRecovery
import com.lomo.domain.model.StorageTimestampFormats

internal const val MEMO_REVISION_PREVIEW_MAX_LENGTH = 280
private const val MEMO_REVISION_PREVIEW_SUFFIX = "..."

internal fun buildMemoRevisionPreview(content: String): String =
    if (content.length <= MEMO_REVISION_PREVIEW_MAX_LENGTH) {
        content
    } else {
        content.take(MEMO_REVISION_PREVIEW_MAX_LENGTH - MEMO_REVISION_PREVIEW_SUFFIX.length) +
            MEMO_REVISION_PREVIEW_SUFFIX
    }

internal fun resolveMemoRevisionBody(
    rawContent: String,
    storedContent: String,
    storedTimestamp: Long,
    dateKey: String,
): ResolvedMemoRevisionBody {
    StoredMemoRecovery
        .recoverOrNull(
            rawContent = rawContent,
            storedContent = storedContent,
            storedTimestamp = storedTimestamp,
            dateKey = dateKey,
        )?.let { recovered ->
            return ResolvedMemoRevisionBody(
                content = recovered.content,
                timestamp = recovered.timestamp,
            )
        }

    val hasTimestampHeader =
        rawContent
            .lineSequence()
            .firstOrNull()
            ?.let(StorageTimestampFormats::parseMemoHeaderLine) != null
    return ResolvedMemoRevisionBody(
        content = if (hasTimestampHeader) storedContent else rawContent.trim(),
        timestamp = storedTimestamp,
    )
}

internal data class ResolvedMemoRevisionBody(
    val content: String,
    val timestamp: Long,
)
