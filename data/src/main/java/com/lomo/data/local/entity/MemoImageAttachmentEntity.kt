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
