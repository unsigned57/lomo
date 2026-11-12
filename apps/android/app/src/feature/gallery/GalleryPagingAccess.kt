package com.lomo.app.feature.gallery

/** Mosaic geometry can reorder memos; paging demand uses their source indices. */
internal fun galleryBandPagingIndex(band: GalleryMosaicLayout, memoIndexById: Map<String, Int>): Int =
    band.tiles.maxOf { memoIndexById.getValue(it.memoId) }
