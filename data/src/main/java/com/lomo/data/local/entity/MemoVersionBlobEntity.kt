package com.lomo.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memo_version_blob")
data class MemoVersionBlobEntity(
    @PrimaryKey val blobHash: String,
    val storagePath: String,
    val byteSize: Long,
    val contentEncoding: String,
    val createdAt: Long,
)
