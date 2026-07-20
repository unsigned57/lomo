package com.lomo.data.testing

import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentAnalysis
import com.lomo.domain.model.MediaFileExtensions

internal fun projectedMemoEntity(
    memo: Memo,
    analysis: MemoContentAnalysis = analysisFromMemo(memo),
): MemoEntity = MemoProjectionProjector.projectActive(memo, analysis).entity

internal fun projectedTrashMemoEntity(
    memo: Memo,
    analysis: MemoContentAnalysis = analysisFromMemo(memo),
): TrashMemoEntity =
    MemoProjectionProjector.projectTrash(memo.copy(isDeleted = true), analysis).entity

private fun analysisFromMemo(memo: Memo): MemoContentAnalysis {
    val attachments = memo.imageUrls
    val audio = attachments.filter(MediaFileExtensions::hasAudioExtension)
    val images = attachments.filterNot(MediaFileExtensions::hasAudioExtension)
    return MemoContentAnalysis(
        hasTodo = false,
        hasAttachment = attachments.isNotEmpty(),
        hasUrl = false,
        tags = memo.tags,
        imageUrls = images,
        audioUrls = audio,
    )
}
