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
 * - Unit under test: MemoVersionHistoryUiMapper
 * - Behavior focus: history revision content must resolve image references the same way as normal memo cards.
 * - Observable outcomes: processed history content rewrites relative Markdown image paths into loadable resolved URLs.
 * - Red phase: Fails before the fix because the history sheet uses raw revision content directly, so relative image paths are never resolved for rendering.
 * - Excludes: Compose rendering, image decoding, and version-history coordinator state transitions.
 */
class MemoVersionHistoryUiMapperTest : AppFunSpec() {
    private val mapper = MemoVersionHistoryUiMapper()

    init {
        test("mapToUiModels resolves relative markdown image paths for history revisions") {
            val cachedUri = mockk<android.net.Uri>()
            every { cachedUri.toString() } returns "content://images/foo%20bar.png"
            val revisions =
                listOf(
                    revision(
                        revisionId = "r1",
                        content = "![cover](assets/foo%20bar.png)",
                    ),
                )

            val models =
                mapper.mapToUiModels(
                    revisions = revisions,
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = mapOf("foo bar.png" to cachedUri),
                )

            (models.single().processedContent) shouldBe ("![cover](content://images/foo%20bar.png)")
        }
    }

    init {
        test("mapToUiModels reuses unchanged cached models when a later page is appended") {
            val firstPage =
                mapper.mapToUiModels(
                    revisions =
                        listOf(
                            revision(
                                revisionId = "r1",
                                content = "first",
                            ),
                        ),
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = emptyMap(),
                )

            val secondPage =
                mapper.mapToUiModels(
                    revisions =
                        listOf(
                            revision(
                                revisionId = "r1",
                                content = "first",
                            ),
                            revision(
                                revisionId = "r2",
                                content = "second",
                            ),
                        ),
                    rootPath = "/memo",
                    imagePath = null,
                    imageMap = emptyMap(),
                )

            ((secondPage.first()) === (firstPage.first())) shouldBe true
            (secondPage.map(MemoVersionHistoryUiModel::processedContent)) shouldBe (listOf("first", "second"))
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
