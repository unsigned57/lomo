package com.lomo.data.local.entity

import androidx.room.*

@Entity(
    tableName = "file_sync_metadata",
    primaryKeys = ["filename", "isTrash"],
)
data class FileSyncEntity(
    val filename: String,
    val lastModified: Long,
    val isTrash: Boolean = false,
)
