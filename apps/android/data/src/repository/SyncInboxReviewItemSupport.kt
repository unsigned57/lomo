package com.lomo.data.repository

import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState

internal fun blockedInboxReviewFile(
    relativePath: String,
    lastModified: Long,
    message: String,
): SyncReviewItem =
    SyncReviewItem(
        relativePath = INBOX_PREFIX + relativePath,
        localContent = null,
        incomingContent = null,
        isBinary = false,
        incomingLastModified = lastModified,
        state = SyncReviewItemState.BLOCKED,
        message = message,
    )

internal fun List<String>.reviewMessageOrNull(): String? =
    takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "Missing attachments: ")
