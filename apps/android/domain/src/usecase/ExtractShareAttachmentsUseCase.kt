package com.lomo.domain.usecase

import com.lomo.domain.model.ShareAttachmentExtractionResult
import com.lomo.domain.model.markdown.toMemoContentAnalysis
import com.lomo.domain.repository.MarkdownWorkspaceRepository

class ExtractShareAttachmentsUseCase(
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
) {
    operator fun invoke(content: String): ShareAttachmentExtractionResult {
        val analysis = markdownWorkspaceRepository.renderMarkdown(content).toMemoContentAnalysis()
        val localAttachmentPaths =
            (analysis.imageUrls + analysis.audioUrls)
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
