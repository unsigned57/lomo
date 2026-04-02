package com.lomo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "local_file_state",
    primaryKeys = ["filename", "isTrash"],
)
data class LocalFileStateEntity(
    val filename: String,
    val isTrash: Boolean = false,
    @ColumnInfo(name = "saf_uri") val safUri: String? = null,
    @ColumnInfo(name = "last_known_modified_time") val lastKnownModifiedTime: Long,
    @ColumnInfo(name = "missing_since") val missingSince: Long? = null,
    @ColumnInfo(name = "missing_count") val missingCount: Int = 0,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long = 0L,
)
