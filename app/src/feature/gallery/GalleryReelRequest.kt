package com.lomo.app.feature.gallery

import com.lomo.app.feature.main.MemoUiModel
import kotlinx.collections.immutable.ImmutableList

data class GalleryReelRequest(
    val memos: ImmutableList<MemoUiModel>,
    val initialMemoIndex: Int,
    val initialImageIndex: Int,
)

