package com.lomo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_sync_conflict")
data class PendingSyncConflictEntity(
    @PrimaryKey
    @ColumnInfo(name = "backend") val backend: String,
    @ColumnInfo(name = "session_kind") val sessionKind: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
)
