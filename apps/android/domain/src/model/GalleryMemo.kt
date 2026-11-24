package com.lomo.domain.model

data class GalleryMemo(
    val id: String,
    val timestamp: Long,
    val updatedAt: Long = timestamp,
    val dateKey: String,
    val timePart: String = "",
    val imageUrls: List<String>,
    val isPinned: Boolean = false,
    val contentRevision: Long? = null,
    val fileFingerprint: String? = null,
) {
    fun displayTimestamp(): String = if (timePart.isNotBlank()) "$dateKey $timePart" else dateKey
}
