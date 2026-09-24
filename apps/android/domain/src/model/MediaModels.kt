package com.lomo.domain.model

enum class MediaCategory {
    IMAGE,
    VOICE,
}

@JvmInline
value class MediaEntryId(
    val raw: String,
)

/**
 * Display descriptor for one committed media file: the filesystem/provider location plus the
 * Rust-witnessed content identity. Location is not identity — the same content may move and the
 * same path may receive different bytes, so caches key on `contentId` while rendering uses
 * `location`. `contentId` is null only for provider-listed media with no digest witness.
 */
data class MediaImageDescriptor(
    val location: StorageLocation,
    val contentId: String?,
)
