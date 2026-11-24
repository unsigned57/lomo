package com.lomo.app.feature.gallery

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class GalleryUiItem(
    val memoId: String,
    val timestamp: Long,
    val updatedAt: Long,
    val dateKey: String,
    val timePart: String,
    val imageUrls: ImmutableList<String>,
    val isPinned: Boolean = false,
) {
    fun displayTimestamp(): String = if (timePart.isNotBlank()) "$dateKey $timePart" else dateKey
}
