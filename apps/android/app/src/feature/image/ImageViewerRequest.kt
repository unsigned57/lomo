package com.lomo.app.feature.image

import com.lomo.app.feature.main.MemoUiModel

/** A memo image always travels with the snapshot that supplied its media and tags. */
data class ImageViewerRequest(val memo: MemoUiModel, val initialIndex: Int) {
    init {
        require(initialIndex in memo.imageUrls.indices) { "Viewer index must address a memo image" }
    }
    val imageUrls: List<String> get() = memo.imageUrls
    val memoId: String get() = memo.memo.id
}

fun createImageViewerRequest(memo: MemoUiModel, clickedUrl: String): ImageViewerRequest {
    val index = memo.imageUrls.indexOf(clickedUrl)
    require(index >= 0) { "Clicked image does not belong to the memo snapshot" }
    return ImageViewerRequest(memo = memo, initialIndex = index)
}
