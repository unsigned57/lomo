package com.lomo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "s3_local_change_journal")
data class S3LocalChangeJournalEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val filename: String,
    @ColumnInfo(name = "change_type") val changeType: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
