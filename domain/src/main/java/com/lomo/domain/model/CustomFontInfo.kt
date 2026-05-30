package com.lomo.domain.model

/**
 * Metadata for a user-imported custom font.
 *
 * @property id Stable identifier (filename inside the data-layer storage directory). Persisted
 *   inside [FontPreference.UserImported].
 * @property displayName Human-readable label derived from the original source filename.
 * @property sizeBytes Size of the imported font file in bytes. Useful for the management UI.
 */
data class CustomFontInfo(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
)
