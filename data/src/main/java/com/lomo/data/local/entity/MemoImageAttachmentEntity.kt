package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "MemoImageAttachment",
    primaryKeys = ["memoId", "imagePath"],
    indices = [Index(value = ["memoId"]), Index(value = ["imagePath"])],
)
data class MemoImageAttachmentEntity(
    val memoId: String,
    val imagePath: String,
)

fun MemoEntity.toImageAttachmentRefs(): List<MemoImageAttachmentEntity> =
    imageUrls.toMemoImageAttachmentRefs(id)

fun TrashMemoEntity.toImageAttachmentRefs(): List<MemoImageAttachmentEntity> =
    imageUrls.toMemoImageAttachmentRefs(id)

private fun String.toMemoImageAttachmentRefs(memoId: String): List<MemoImageAttachmentEntity> =
    split(',')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { imagePath ->
            MemoImageAttachmentEntity(
                memoId = memoId,
                imagePath = imagePath,
            )
        }.toList()
