package com.lomo.data.source

/** Immutable input to one scan; settings are never re-read halfway through provider I/O. */
data class MediaStorageSelection(val workspaceLocation: String?, val imageLocation: String?)
