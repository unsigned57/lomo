package com.lomo.data.local.entity

import androidx.room.*

@Entity(tableName = "file_sync_metadata")
data class FileSyncEntity(
    @PrimaryKey val filename: String,
    val lastModified: Long,
    val isTrash: Boolean = false,
)
