package com.lomo.data.local.entity

import androidx.room3.Entity

@Entity(
    tableName = "image_location_cache",
    primaryKeys = ["name"],
)
data class ImageLocationCacheEntity(
    val name: String,
    val uri: String,
)
