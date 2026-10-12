package com.lomo.domain.usecase

import com.lomo.domain.model.ShareAttachmentExtractionResult
import com.lomo.domain.model.markdown.MarkdownAttachment
import com.lomo.domain.repository.MarkdownWorkspaceRepository

class ExtractShareAttachmentsUseCase(
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
) {
    operator fun invoke(content: String): ShareAttachmentExtractionResult {
        // Share contracts extract every owner-classified attachment — image, video, audio, and
        // generic file — filtered only by locality, never by extension re-classification.
        val localAttachmentPaths =
            markdownWorkspaceRepository
                .renderMarkdown(content)
                .attachments
                .map(MarkdownAttachment::destination)
                .filter(::isLocalAttachmentPath)
                .distinct()
        return ShareAttachmentExtractionResult(
            localAttachmentPaths = localAttachmentPaths,
            attachmentUris = localAttachmentPaths.associateWith { it },
        )
    }

    private fun isLocalAttachmentPath(path: String): Boolean =
        path.isNotEmpty() &&
            !path.startsWith("http://", ignoreCase = true) &&
            !path.startsWith("https://", ignoreCase = true)
}
