package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "version_commit",
    indices = [Index(value = ["createdAt"]), Index(value = ["batchId"])],
)
data class MemoVersionCommitEntity(
    @PrimaryKey val commitId: String,
    val createdAt: Long,
    val origin: String,
    val actor: String,
    val batchId: String?,
    val summary: String,
)
