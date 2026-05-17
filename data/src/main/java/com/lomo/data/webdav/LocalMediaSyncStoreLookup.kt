package com.lomo.data.webdav

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.SyncDirectoryLayout

internal suspend fun locateMediaFile(
    relativePath: String,
    layout: SyncDirectoryLayout,
    cachedRoots: List<MediaRoot>?,
    dataStore: LomoDataStore,
): LocatedMediaFile? {
    val stripped = stripMediaSyncPrefix(relativePath.trim().trimStart('/'))
    val category = mediaCategoryForPath(stripped, layout)
    val filename = stripped.substringAfter('/', "")
    val root =
        category?.let { resolvedCategory ->
            resolveConfiguredMediaRoots(
                cachedRoots = cachedRoots,
                dataStore = dataStore,
            ).firstOrNull { it.category == resolvedCategory }
        }
    return if (filename.isNotBlank() && root != null) {
        LocatedMediaFile(root = root, filename = filename)
    } else {
        null
    }
}
