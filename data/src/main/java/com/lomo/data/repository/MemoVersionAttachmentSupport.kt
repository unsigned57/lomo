package com.lomo.data.repository

import com.lomo.domain.model.MediaFileExtensions
import java.util.Locale

internal fun resolveAttachmentCategory(filename: String): WorkspaceMediaCategory? {
    val extension = MediaFileExtensions.extensionOf(filename)
    return when {
        extension in MediaFileExtensions.IMAGE -> WorkspaceMediaCategory.IMAGE
        extension in MediaFileExtensions.AUDIO -> WorkspaceMediaCategory.VOICE
        else -> null
    }
}

internal fun String.toAttachmentCategory(): WorkspaceMediaCategory? =
    when {
        startsWith(LOGICAL_IMAGE_PREFIX) -> WorkspaceMediaCategory.IMAGE
        startsWith(LOGICAL_VOICE_PREFIX) -> WorkspaceMediaCategory.VOICE
        else -> null
    }

internal fun contentEncodingForAttachment(
    category: WorkspaceMediaCategory,
    filename: String,
): String =
    when (category) {
        WorkspaceMediaCategory.IMAGE ->
            "image/${filename.substringAfterLast('.', "bin").lowercase(Locale.ROOT)}"
        WorkspaceMediaCategory.VOICE ->
            "audio/${filename.substringAfterLast('.', "bin").lowercase(Locale.ROOT)}"
    }

internal suspend fun resolveRevisionAttachment(
    path: String,
    workspaceMediaAccess: WorkspaceMediaAccess,
): ResolvedMemoRevisionAttachment? {
    val filename = path.substringAfterLast('/')
    val category = resolveAttachmentCategory(filename) ?: return null
    val bytes = workspaceMediaAccess.readFileBytes(category = category, filename = filename) ?: return null
    val logicalPath = "${category.logicalPrefix}$filename"
    val contentEncoding = contentEncodingForAttachment(category, filename)
    return ResolvedMemoRevisionAttachment(
        logicalPath = logicalPath,
        contentEncoding = contentEncoding,
        bytes = bytes,
    )
}
