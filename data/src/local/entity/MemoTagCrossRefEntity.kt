package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "MemoTagCrossRef",
    primaryKeys = ["memoId", "tag"],
    foreignKeys =
        [
            ForeignKey(
                entity = MemoEntity::class,
                parentColumns = ["id"],
                childColumns = ["memoId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["memoId"]), Index(value = ["tag"])],
)
data class MemoTagCrossRefEntity(
    val memoId: String,
    val tag: String,
)
