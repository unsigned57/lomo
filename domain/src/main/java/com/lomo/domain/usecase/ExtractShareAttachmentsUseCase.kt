package com.lomo.domain.usecase

import com.lomo.domain.model.ShareAttachmentExtractionResult

class ExtractShareAttachmentsUseCase {
    operator fun invoke(content: String): ShareAttachmentExtractionResult {
        val analysis = MemoContentAnalyzer.analyze(content)
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
