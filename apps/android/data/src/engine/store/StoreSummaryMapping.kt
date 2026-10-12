package com.lomo.data.engine.store

import com.lomo.data.reminder.toDomainRecurrence

internal fun com.lomo.nativebridge.StoreMemoSummary.toStoreSummary(): StoreMemoSummary =
    StoreMemoSummary(
        memoId = memoId,
        sourcePath = sourcePath,
        fileFingerprint = fileFingerprint,
        updatedAtMs = updatedAtMs,
        createdAtMs = createdAtMs,
        hasTodo = hasTodo,
        hasUrl = hasUrl,
        hasAttachment = hasAttachment,
        isPinned = isPinned,
        isTrashed = isTrashed,
        bodyPreview = bodyPreview,
        contentRevision = contentRevision.toLong(),
        rank = rank,
        tags = tags,
        imageUrls = imageUrls,
        reminders = reminders.map { reminder -> reminder.toDomainMarker() },
        isPending = isPending,
        charCount = charCount,
    )

private fun com.lomo.nativebridge.WorkspaceReminderReference.toDomainMarker():
    com.lomo.domain.model.ReminderMarker =
    com.lomo.domain.model.ReminderMarker(
        dueAtLocal = dueAtLocal,
        dueAt =
            java.time.LocalDateTime.parse(
                dueAtLocal,
                com.lomo.data.reminder.REMINDER_DUE_AT_FORMATTER,
            ),
        repeatCount = repeatCount.toInt(),
        firedCount = firedCount.toInt(),
        done = done,
        intervalMinutes = intervalMinutes.toInt(),
        recurrence = recurrence.toDomainRecurrence(),
        reference =
            com.lomo.domain.model.ReminderReference(
                opaqueId = opaqueId,
                revision = revision,
                memoIdentity = memoIdentity,
                sourceSpan =
                    com.lomo.domain.model.markdown.MarkdownSourceSpan(
                        startByte = sourceStart,
                        endByte = sourceEnd,
                    ),
                tokenFingerprint = tokenFingerprint,
                fingerprintOrdinal = fingerprintOrdinal,
                embeddedId = embeddedId,
            ),
        token = token,
    )

internal fun StoreMemoFilters.toNativeFilters(): com.lomo.nativebridge.StoreMemoFilters =
    com.lomo.nativebridge.StoreMemoFilters(
        tag = tag,
        tagSubtree = tagSubtree,
        dateFromInclusiveMs = dateFromInclusiveMs,
        dateUntilExclusiveMs = dateUntilExclusiveMs,
        hasTodo = hasTodo,
        hasAttachment = hasAttachment,
        hasUrl = hasUrl,
        pinnedOnly = pinnedOnly,
        includeTrash = includeTrash,
        trashOnly = trashOnly,
    )
