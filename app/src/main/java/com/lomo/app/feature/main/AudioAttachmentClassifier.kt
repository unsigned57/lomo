package com.lomo.app.feature.main

import java.util.Locale

private val AUDIO_ATTACHMENT_SUFFIXES = setOf(".m4a", ".mp3", ".aac", ".wav", ".ogg")

/**
 * Returns `true` when the provided attachment path targets a Lomo voice note or similar audio
 * file. Used by app-layer filters (e.g. gallery view) that want to exclude memos whose only
 * inline attachment is a voice recording, since those should not render as images.
 */
internal fun isAudioAttachmentPath(path: String): Boolean {
    val normalized = path.lowercase(Locale.ROOT)
    return AUDIO_ATTACHMENT_SUFFIXES.any(normalized::endsWith)
}
