package com.lomo.domain.model

enum class MediaCategory {
    IMAGE,
    VOICE,
}

@JvmInline
value class MediaEntryId(
    val raw: String,
)
