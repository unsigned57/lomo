package com.lomo.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class ImageLocationCacheEntity(
    val name: String,
    val uri: String,
)
