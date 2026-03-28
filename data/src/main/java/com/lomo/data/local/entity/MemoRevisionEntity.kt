package com.lomo.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memo_revision",
    indices =
        [
            Index(value = ["memoId", "createdAt"]),
            Index(value = ["memoId", "createdAt", "revisionId"]),
            Index(value = ["memoId", "lifecycleState", "contentHash", "rawMarkdownBlobHash"]),
            Index(value = ["commitId"]),
            Index(value = ["rawMarkdownBlobHash"]),
        ],
)
data class MemoRevisionEntity(
    @PrimaryKey val revisionId: String,
    val memoId: String,
    val parentRevisionId: String?,
    val commitId: String,
    val dateKey: String,
    val lifecycleState: String,
    val rawMarkdownBlobHash: String,
    val contentHash: String,
    val memoTimestamp: Long,
    val memoUpdatedAt: Long,
    val memoContent: String,
    val createdAt: Long,
)
