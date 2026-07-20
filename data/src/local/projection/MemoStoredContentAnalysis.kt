package com.lomo.data.local.projection

import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.entity.decodeStoredMemoStringList
import com.lomo.domain.model.MediaFileExtensions
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentAnalysis

/**
 * Reconstructs storage analysis from already-projected Room/domain facts.
 *
 * Refresh and lifecycle paths must not re-invoke the owner render pipeline over body text when
 * tags/attachments/flags were produced by the same workspace parse that owns identity.
 */
internal fun MemoEntity.toStoredContentAnalysis(): MemoContentAnalysis {
    val attachments = decodeStoredMemoStringList(imageUrls)
    val audioUrls = attachments.filter(MediaFileExtensions::hasAudioExtension)
    val imageOnly = attachments.filterNot(MediaFileExtensions::hasAudioExtension)
    return MemoContentAnalysis(
        hasTodo = hasTodo,
        hasAttachment = hasAttachment || attachments.isNotEmpty(),
        hasUrl = hasUrl,
        tags = decodeStoredMemoStringList(tags),
        imageUrls = imageOnly,
        audioUrls = audioUrls,
    )
}

internal fun TrashMemoEntity.toStoredContentAnalysis(): MemoContentAnalysis {
    val attachments = decodeStoredMemoStringList(imageUrls)
    val audioUrls = attachments.filter(MediaFileExtensions::hasAudioExtension)
    val imageOnly = attachments.filterNot(MediaFileExtensions::hasAudioExtension)
    return MemoContentAnalysis(
        hasTodo = false,
        hasAttachment = attachments.isNotEmpty(),
        hasUrl = false,
        tags = decodeStoredMemoStringList(tags),
        imageUrls = imageOnly,
        audioUrls = audioUrls,
    )
}

/**
 * Attachment/tag facts already carried on a domain [Memo]. Task/URL flags are unknown here and stay
 * false; callers that must persist active query flags should use a single owner [analyze] on free
 * content or [MemoEntity.toStoredContentAnalysis] when Room flags exist.
 */
internal fun Memo.toAttachmentContentAnalysis(): MemoContentAnalysis {
    val audioUrls = imageUrls.filter(MediaFileExtensions::hasAudioExtension)
    val imageOnly = imageUrls.filterNot(MediaFileExtensions::hasAudioExtension)
    return MemoContentAnalysis(
        hasTodo = false,
        hasAttachment = imageUrls.isNotEmpty(),
        hasUrl = false,
        tags = tags,
        imageUrls = imageOnly,
        audioUrls = audioUrls,
    )
}

internal fun Memo.withContentAnalysis(analysis: MemoContentAnalysis): Memo =
    copy(
        tags = analysis.tags,
        imageUrls = (analysis.imageUrls + analysis.audioUrls).distinct(),
    )
