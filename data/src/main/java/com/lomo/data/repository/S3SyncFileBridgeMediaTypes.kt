package com.lomo.data.repository

import java.util.Locale

internal fun isSupportedAttachmentPath(relativePath: String): Boolean {
    val extension = relativePath.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in S3_SYNC_IMAGE_EXTENSIONS || extension in S3_SYNC_VOICE_EXTENSIONS
}
