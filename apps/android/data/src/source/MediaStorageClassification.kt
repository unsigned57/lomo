package com.lomo.data.source

import com.lomo.nativebridge.MediaClass
import com.lomo.nativebridge.attachmentMediaClass

/**
 * Asks the Rust workspace media owner whether a storage file name is an image attachment.
 *
 * Storage enumeration uses the same owner classifier as render and query facts; Kotlin never
 * inspects MIME prefixes or extensions itself.
 */
internal fun isImageAttachmentFileName(name: String): Boolean =
    attachmentMediaClass(name) == MediaClass.IMAGE
