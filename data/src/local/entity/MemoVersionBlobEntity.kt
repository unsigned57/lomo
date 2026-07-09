package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "memo_version_blob")
data class MemoVersionBlobEntity(
    @PrimaryKey val blobHash: String,
    val storagePath: String,
    val byteSize: Long,
    val contentEncoding: String,
    val createdAt: Long,
)
