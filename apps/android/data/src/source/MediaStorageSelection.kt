package com.lomo.data.source

/** Immutable input to one scan; settings are never re-read halfway through provider I/O. */
data class MediaStorageSelection(val workspaceLocation: String?, val imageLocation: String?)

sealed interface ImageScanScope {
    data object SelectedDirectory : ImageScanScope

    data class Notes(val includeWorkspaceImages: Boolean) : ImageScanScope

    fun includesDirectory(path: String): Boolean = when (this) {
        SelectedDirectory -> true

        is Notes -> when (path.substringBefore('/')) {
            "media" -> true
            "images" -> includeWorkspaceImages
            else -> false
        }
    }

    fun includesFile(path: String): Boolean = '/' in path || this !is Notes || includeWorkspaceImages
}
