/*
 * Behavior Contract:
 * - Unit under test: MemoUiMapper
 * - Owning layer: app
 * - Priority tier: P2
 * - Capability: map memo image-gallery URLs from the persisted canonical projection while keeping
 *   markdown rendering free to resolve inline presentation content.
 *
 * Scenarios:
 * - Given memo content contains an inline markdown image that is not in the stored projection, when
 *   a UI model is mapped, then the processed markdown can resolve that image for rendering but the
 *   image-gallery URLs come only from memo.imageUrls.
 * - Given the projection contains an audio attachment, when image-gallery URLs are mapped, then the
 *   app presentation filter excludes the audio path without redefining attachment semantics.
 *
 * Observable outcomes:
 * - Processed markdown text and MemoUiModel.imageUrls.
 *
 * TDD proof:
 * - RED before the projection fix because MemoUiMapper extracted image-gallery URLs from processed
 *   markdown content, so `inline.png` appeared in MemoUiModel.imageUrls and the stored projection was ignored.
 *
 * Excludes:
 * - Domain content analysis regex behavior, Room projection persistence, Compose rendering, and
 *   image cache lookup.
 */
package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain

class MemoUiMapperProjectionTest : AppFunSpec() {
    init {
        test("given inline image differs from projection when mapped then gallery urls consume projection only") {
            val mapper = MemoUiMapper()
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 1L,
                    updatedAt = 1L,
                    content = "Inline ![inline](inline.png)",
                    rawContent = "- 10:00 Inline ![inline](inline.png)",
                    dateKey = "2026_05_24",
                    imageUrls = listOf("projected.png", "recordings/clip.m4a"),
                )

            val uiModel =
                mapper.mapToUiModel(
                    memo = memo,
                    rootPath = "/memo-root",
                    imagePath = null,
                    imageMap = emptyMap(),
                    precomputeMarkdown = false,
                )

            uiModel.processedContent shouldContain "![inline](/memo-root/inline.png)"
            uiModel.imageUrls shouldContainExactly listOf("/memo-root/projected.png")
        }
    }
}
