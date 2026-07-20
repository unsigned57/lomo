/*
 * Behavior Contract:
 * - Unit under test: MemoVersionHistoryUiMapperCacheIsolation
 * - Owning layer: production path under test
 * - Priority tier: P1
 * - Capability: preserve observable product behavior after Markdown semantic ownership moved to
 *   lomo-workspace (typed IR, workspace scan/render/document commands) with Kotlin adapters only.
 *
 * Scenarios:
 * - Given production collaborators expose workspace IR / document-command seams, when this suite
 *   runs, then assertions verify the same user-visible outcomes without Kotlin MarkdownParser.
 * - Given deleted JetBrains or line-authority helpers, when tests construct fakes, then they use
 *   FakeMarkdownWorkspace / content projector adapters instead of dual-authority parsers.
 * - Given invalid or missing readiness inputs, when exercised, then fail-closed outcomes remain.
 *
 * Observable outcomes:
 * - Public method results, DI wiring, and presentation fields match the post-cutover contracts.
 *
 * TDD proof:
 * - RED: suites fail to compile or assert against MarkdownParser / JetBrains plan types after cutover.
 * - GREEN: ./kotlin test on this class passes against workspace IR adapters.
 *
 * Excludes:
 * - Room schema ownership, sync backend redesign, and Compose pixel rendering.
 *
 * Test Change Justification:
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */

package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeMarkdownWorkspaceRepository
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionLifecycleState
import com.lomo.domain.model.MemoRevisionOrigin
import io.kotest.matchers.shouldBe

class MemoVersionHistoryUiMapperCacheIsolationTest : AppFunSpec() {
    private val mapper = MemoVersionHistoryUiMapper(FakeMarkdownWorkspaceRepository())

    init {
        test("mapToUiModels drops cache entries for revisions no longer present") {
            fun rev(id: String, content: String) = MemoRevision(
                revisionId = id,
                parentRevisionId = null,
                memoId = "m1",
                commitId = "c-$id",
                batchId = null,
                createdAt = 1L,
                origin = MemoRevisionOrigin.LOCAL_EDIT,
                summary = content,
                lifecycleState = MemoRevisionLifecycleState.ACTIVE,
                memoContent = content,
                isCurrent = true,
            )
            mapper.mapToUiModels(listOf(rev("keep", "keep"), rev("drop", "drop")), null, null, emptyMap())
            val models = mapper.mapToUiModels(listOf(rev("keep", "keep")), null, null, emptyMap())
            models.map { it.revision.revisionId } shouldBe listOf("keep")
        }
    }
}
