package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "memo_revision_asset",
    primaryKeys = ["revisionId", "logicalPath"],
    indices = [Index(value = ["blobHash"]), Index(value = ["revisionId", "logicalPath"])],
)
data class MemoRevisionAssetEntity(
    val revisionId: String,
    val logicalPath: String,
    val blobHash: String,
    val contentEncoding: String,
)
