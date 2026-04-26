package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "s3_local_change_journal")
data class S3LocalChangeJournalEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val filename: String,
    @ColumnInfo(name = "change_type") val changeType: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
