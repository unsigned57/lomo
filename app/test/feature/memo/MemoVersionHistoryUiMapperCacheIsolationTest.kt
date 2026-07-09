package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/*
 * Test Contract:
 * - Unit under test: MemoVersionHistoryUiMapper cache invalidation policy
 * - Behavior focus: unrelated image-map churn must not invalidate already processed history-card content.
 * - Observable outcomes: mapping the same revision after an unrelated image-map change reuses the cached
 *   MemoVersionHistoryUiModel instance.
 * - Red phase: Fails before the fix because any imageMap inequality clears the entire history-model cache,
 *   forcing all visible revisions to rebuild processedContent.
 * - Excludes: Compose rendering, image decoding, and version-history pagination state.
 */
class MemoVersionHistoryUiMapperCacheIsolationTest : AppFunSpec() {
    private val mapper = MemoVersionHistoryUiMapper()

    init {
        test("mapToUiModels keeps cached revision when image map changes outside referenced attachments") {
            val referencedUri = mockk<android.net.Uri>()
            every { referencedUri.toString() } returns "content://images/foo%20bar.png"
            val unrelatedUri = mockk<android.net.Uri>()
            every { unrelatedUri.toString() } returns "content://images/unrelated.png"
            val revisions =
                listOf(
                    revision(
                        revisionId = "r1",
                        content = "![cover](assets/foo%20bar.png)",
                    ),
                )

            val firstPage =
                mapper.mapToUiModels(
                    revisions = revisions,
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = mapOf("foo bar.png" to referencedUri),
                )

            val secondPage =
                mapper.mapToUiModels(
                    revisions = revisions,
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap =
                        mapOf(
                            "foo bar.png" to referencedUri,
                            "unrelated.png" to unrelatedUri,
                        ),
                )

            ((secondPage.single()) === (firstPage.single())) shouldBe true
        }
    }

}

private fun revision(
    revisionId: String,
    content: String,
): MemoRevision =
    MemoRevision(
        revisionId = revisionId,
        parentRevisionId = null,
        memoId = "memo-1",
        commitId = "commit-$revisionId",
        batchId = null,
        createdAt = 1L,
        origin = MemoRevisionOrigin.LOCAL_EDIT,
        summary = "",
        lifecycleState = MemoRevisionLifecycleState.ACTIVE,
        memoContent = content,
        isCurrent = false,
    )
