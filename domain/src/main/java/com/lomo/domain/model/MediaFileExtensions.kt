package com.lomo.domain.model

import java.util.Locale

object MediaFileExtensions {
    val AUDIO = setOf("m4a", "mp3", "aac", "wav", "ogg")
    val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif")

    fun extensionOf(candidate: String): String =
        candidate
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)

    fun hasAudioExtension(candidate: String): Boolean = extensionOf(candidate) in AUDIO

    fun hasImageExtension(candidate: String): Boolean = extensionOf(candidate) in IMAGE
}
