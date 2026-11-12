package com.lomo.app.testing.fakes

import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import kotlinx.collections.immutable.toImmutableList

fun imageMemoFixture(
    images: List<String> = listOf(
        "a.jpg",
        "b.jpg",
    ),
    tags: List<String> = listOf("photos"),
): MemoUiModel = MemoUiModel(
    memo = Memo(
        id = "image-memo",
        timestamp = 0L,
        content = "note",
        rawContent = "note",
        dateKey = "1970_01_01",
        tags = tags,
        imageUrls = images,
    ),
    processedContent = "note",
    renderDocument = MarkdownRenderDocument(
        sourceByteLength = 4uL,
        plainText = "note",
        tagNames = tags,
        attachments = emptyList(),
        blocks = emptyList(),
    ),
    tags = tags.toImmutableList(),
    imageUrls = images.toImmutableList(),
)
