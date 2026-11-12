package com.lomo.app.feature.gallery

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toPersistentMap

/** Entry geometry belongs to this screen session, independently of the image decoder's LRU. */
internal sealed interface GalleryEntryGeometry {
    data object Resolving : GalleryEntryGeometry
    data class Ready(val aspectByImageUrl: ImmutableMap<String, Float>) : GalleryEntryGeometry
}

internal fun resolveGalleryEntryGeometry(
    previous: GalleryEntryGeometry,
    entryUrls: List<String>,
    aspectByImageUrl: Map<String, Float>,
): GalleryEntryGeometry {
    if (previous is GalleryEntryGeometry.Ready) return previous
    if (entryUrls.isEmpty() || entryUrls.any { it !in aspectByImageUrl }) return GalleryEntryGeometry.Resolving
    return GalleryEntryGeometry.Ready(entryUrls.associateWith { aspectByImageUrl.getValue(it) }.toPersistentMap())
}
