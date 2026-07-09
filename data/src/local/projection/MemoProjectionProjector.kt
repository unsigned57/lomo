package com.lomo.data.local.projection

import com.lomo.data.local.entity.MemoEntity
import com.lomo.data.local.entity.MemoImageAttachmentEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.local.entity.TrashMemoEntity
import com.lomo.data.local.entity.encodeStoredMemoStringList
import com.lomo.data.util.SearchTokenizer
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoStatisticsCalculator
import com.lomo.domain.usecase.MemoContentAnalyzer

object MemoProjectionProjector {
    fun projectActive(memo: Memo): ActiveMemoProjection {
        val contentProjection = memo.content.toContentProjection()
        val statisticsProjection =
            MemoStatisticsCalculator.projectMemo(
                timestamp = memo.timestamp,
                content = memo.content,
            )
        val entity =
            MemoEntity(
                id = memo.id,
                timestamp = memo.timestamp,
                updatedAt = memo.updatedAt,
                content = memo.content,
                searchContent = SearchTokenizer.tokenize(memo.content),
                rawContent = memo.rawContent,
                date = memo.dateKey,
                tags = encodeStoredMemoStringList(contentProjection.tags),
                imageUrls = encodeStoredMemoStringList(contentProjection.attachmentUrls),
                hasTodo = contentProjection.hasTodo,
                hasAttachment = contentProjection.hasAttachment,
                hasUrl = contentProjection.hasUrl,
                statisticsWordCount = statisticsProjection.wordCount,
                statisticsCharacterCount = statisticsProjection.characterCount,
                geoLocation = memo.geoLocation,
            )
        return ActiveMemoProjection(
            entity = entity,
            tagRefs =
                contentProjection.tags.map { tag ->
                    MemoTagCrossRefEntity(memoId = memo.id, tag = tag)
                },
            imageRefs = contentProjection.toImageRefs(memo.id),
        )
    }

    fun projectTrash(memo: Memo): TrashMemoProjection {
        val contentProjection = memo.content.toContentProjection()
        val entity =
            TrashMemoEntity(
                id = memo.id,
                timestamp = memo.timestamp,
                updatedAt = memo.updatedAt,
                content = memo.content,
                rawContent = memo.rawContent,
                date = memo.dateKey,
                tags = encodeStoredMemoStringList(contentProjection.tags),
                imageUrls = encodeStoredMemoStringList(contentProjection.attachmentUrls),
            )
        return TrashMemoProjection(
            entity = entity,
            imageRefs = contentProjection.toImageRefs(memo.id),
        )
    }

    private fun String.toContentProjection(): MemoContentProjection {
        val analysis = MemoContentAnalyzer.analyze(this)
        return MemoContentProjection(
            hasTodo = analysis.hasTodo,
            hasAttachment = analysis.hasAttachment,
            hasUrl = analysis.hasUrl,
            tags = analysis.tags,
            attachmentUrls = (analysis.imageUrls + analysis.audioUrls).distinct(),
        )
    }

    private fun MemoContentProjection.toImageRefs(memoId: String): List<MemoImageAttachmentEntity> =
        attachmentUrls.map { imagePath ->
            MemoImageAttachmentEntity(memoId = memoId, imagePath = imagePath)
        }
}

data class ActiveMemoProjection(
    val entity: MemoEntity,
    val tagRefs: List<MemoTagCrossRefEntity>,
    val imageRefs: List<MemoImageAttachmentEntity>,
)

data class TrashMemoProjection(
    val entity: TrashMemoEntity,
    val imageRefs: List<MemoImageAttachmentEntity>,
)

private data class MemoContentProjection(
    val hasTodo: Boolean,
    val hasAttachment: Boolean,
    val hasUrl: Boolean,
    val tags: List<String>,
    val attachmentUrls: List<String>,
)
