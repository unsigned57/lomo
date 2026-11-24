package com.lomo.app.feature.gallery

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class GalleryReelEntry(val memoId: String, val imageUrls: ImmutableList<String>)

data class GalleryReelRequest(
    val entries: ImmutableList<GalleryReelEntry>,
    val initialMemoIndex: Int,
    val initialImageIndex: Int,
)
