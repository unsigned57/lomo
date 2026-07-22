package com.lomo.data.media

/**
 * Android-only presentation MIME hints for SAF create/list and sync Content-Type headers.
 *
 * **Not** content identity. Rust `lomo-media` magic/extension conflict detection is the sole
 * media identity MIME authority after P4-10A. These tables must never decide digest, stage, or
 * promote acceptance.
 *
 * Keep a single copy here; WebDAV/S3/SAF hosts mirror via these helpers only.
 */
object AndroidPresentationMimeTypes {
    private val IMAGE =
        mapOf(
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
            "heic" to "image/heic",
            "heif" to "image/heif",
            "avif" to "image/avif",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
        )

    private val AUDIO =
        mapOf(
            "mp3" to "audio/mpeg",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "m4a" to "audio/mp4",
        )

    const val DEFAULT_IMAGE = "image/jpeg"
    const val DEFAULT_AUDIO = "audio/mp4"
    const val OCTET_STREAM = "application/octet-stream"

    fun imageMimeForExtension(extension: String): String =
        IMAGE[extension.lowercase()] ?: DEFAULT_IMAGE

    fun audioMimeForExtension(extension: String): String =
        AUDIO[extension.lowercase()] ?: DEFAULT_AUDIO

    fun imageMimeForFilename(filename: String): String =
        imageMimeForExtension(filename.substringAfterLast('.', ""))

    fun audioMimeForFilename(filename: String): String =
        audioMimeForExtension(filename.substringAfterLast('.', ""))
}
