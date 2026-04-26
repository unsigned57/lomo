package com.lomo.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "webdav_local_fingerprint")
data class WebDavLocalFingerprintEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    val size: Long? = null,
    val fingerprint: String,
)
