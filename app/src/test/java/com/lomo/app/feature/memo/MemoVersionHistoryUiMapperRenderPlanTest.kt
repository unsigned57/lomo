package com.lomo.app.feature.memo

import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoVersionHistoryUiMapper
 * - Behavior focus: history cards with rich Markdown preview must precompute the shared modern render plan so the
 *   sheet does not rebuild Markdown structure on every visible card interaction.
 * - Observable outcomes: mapped history item carries a non-null precomputed render plan with the expected
 *   sanitized content and block count.
 * - Red phase: Fails before the fix because history items only expose processedContent, forcing Markdown preview
 *   work to be recomputed later during sheet rendering.
 * - Excludes: Compose bottom-sheet state, restore execution, and image decoding.
 */
class MemoVersionHistoryUiMapperRenderPlanTest {
    private val mapper = MemoVersionHistoryUiMapper()

    @Test
    fun `mapToUiModels precomputes markdown preview render plans for history cards`() {
        val models =
            mapper.mapToUiModels(
                revisions =
                    listOf(
                        revision(
                            revisionId = "r1",
                            content =
                                """
                                # Title

                                body line
                                """.trimIndent(),
                        ),
                    ),
                rootPath = null,
                imagePath = null,
                imageMap = emptyMap(),
            )

        val renderPlan = models.single().precomputedRenderPlan
        assertNotNull(renderPlan)
        assertEquals("# Title\n\nbody line", renderPlan?.content)
        assertEquals(2, renderPlan?.totalBlocks)
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
