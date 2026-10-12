package com.lomo.domain.model

/**
 * Media classification owned by the Rust workspace (`attachment_media_class`).
 *
 * Kotlin never derives this value from MIME strings, file extensions, or destination text; the
 * owner projects one of these closed variants through the native boundary and the data layer maps
 * it verbatim. `FILE` covers extensionless, unknown, and non-media destinations.
 */
enum class MediaClass {
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
}
