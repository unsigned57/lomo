package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout

internal fun isWebDavMemoPath(
    path: String,
    layout: SyncDirectoryLayout,
): Boolean {
    val memoPrefix = "$WEBDAV_ROOT/${layout.memoFolder}/"
    return path.startsWith(memoPrefix) && path.endsWith(WEBDAV_MEMO_SUFFIX)
}

internal fun extractWebDavMemoFilename(
    path: String,
    layout: SyncDirectoryLayout,
): String = path.removePrefix("$WEBDAV_ROOT/${layout.memoFolder}/")

internal fun webDavContentTypeForPath(
    path: String,
    layout: SyncDirectoryLayout,
    runtime: WebDavSyncRepositoryContext,
): String =
    if (isWebDavMemoPath(path, layout)) {
        WEBDAV_MARKDOWN_CONTENT_TYPE
    } else {
        runtime.localMediaSyncStore.contentTypeForPath(path, layout)
    }
