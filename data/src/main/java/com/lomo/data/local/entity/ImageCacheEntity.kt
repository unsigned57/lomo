package com.lomo.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "image_cache")
data class ImageCacheEntity(
    @PrimaryKey val filename: String,
    val uriString: String,
    val lastModified: Long,
)
