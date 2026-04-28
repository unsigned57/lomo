package com.lomo.app.feature.main

import com.lomo.domain.model.MediaFileExtensions

/**
 * Returns `true` when the provided attachment path targets a Lomo voice note or similar audio
 * file. Used by app-layer filters (e.g. gallery view) that want to exclude memos whose only
 * inline attachment is a voice recording, since those should not render as images.
 */
internal fun isAudioAttachmentPath(path: String): Boolean = MediaFileExtensions.hasAudioExtension(path)
