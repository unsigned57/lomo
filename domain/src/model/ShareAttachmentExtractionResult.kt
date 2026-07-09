package com.lomo.domain.model

data class ShareAttachmentExtractionResult(
    val localAttachmentPaths: List<String>,
    val attachmentUris: Map<String, String>,
)
