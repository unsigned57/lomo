package com.lomo.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "memo_tag_cross_ref",
    primaryKeys = ["memoId", "tagName"],
    indices = [Index(value = ["memoId"]), Index(value = ["tagName"])],
)
data class MemoTagCrossRef(
    val memoId: String,
    val tagName: String,
)
