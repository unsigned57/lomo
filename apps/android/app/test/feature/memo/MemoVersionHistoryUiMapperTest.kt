/*
 * Behavior Contract:
 * - Unit under test: MemoVersionHistoryUiMapper
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
 * - Given one revision whose body Rust refuses for its presentation budget, when the page is
 *   mapped, then every revision stays listed in order and that one carries Unavailable(code).
 * - TDD proof: RED 2026-11-10 (`./kotlin test --include-module=app
 *   --include-classes='com.lomo.app.feature.memo.MemoVersionHistoryUiMapperTest'`) — the mapper
 *   threw MarkdownRenderContractException; on emulator-5554 opening History on a memo beyond
 *   8192 render nodes crashed the main thread.
 *
 * Excludes:
 * - Room schema ownership, sync backend redesign, and Compose pixel rendering.
 *
 * Test Change Justification:
 * - Reason category: production model change (typed version preview).
 * - Old behavior/assertion being replaced: models exposed a non-null `renderDocument` field.
 * - Why old assertion is no longer correct: a revision body may exceed the owner's presentation
 *   budget; the model now carries `MemoVersionPreview.Rendered` or `.Unavailable` so one oversized
 *   version cannot crash the history sheet.
 * - Coverage preserved by: the same plain-text projections are asserted through
 *   `MemoVersionPreview.Rendered`; the budget-refusal scenario asserts `.Unavailable`.
 * - Why this is not fitting the test to the implementation: the expected rendered text is
 *   unchanged; only the field that carries it gained the failure case.
 * - Production scope: apps/android/app/src/feature/memo/MemoVersionHistoryUiMapper.kt
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

class MemoVersionHistoryUiMapperTest : AppFunSpec() {
    private val mapper = MemoVersionHistoryUiMapper(FakeMarkdownWorkspaceRepository())

    init {
        test("mapToUiModels preserves revision identity order") {
            val revisions = listOf(rev("r1", "a"), rev("r2", "b"))
            val models = mapper.mapToUiModels(revisions, null, null, emptyMap())
            models.map { it.revision.revisionId } shouldBe listOf("r1", "r2")
            models.map { (it.preview as MemoVersionPreview.Rendered).document.plainText } shouldBe listOf("a", "b")
        }

        test("given one revision beyond the render budget when mapped then every revision stays listed") {
            val oversized = "oversized body"
            val failing =
                MemoVersionHistoryUiMapper(
                    FakeMarkdownWorkspaceRepository(
                        onRender = { content ->
                            if (content == oversized) {
                                throw com.lomo.domain.model.markdown.MarkdownRenderContractException(
                                    "render_document_too_large",
                                    "RenderDocumentV1 exceeds the 8192 node limit",
                                )
                            }
                        },
                    ),
                )
            val models =
                io.kotest.assertions.throwables.shouldNotThrowAny {
                    failing.mapToUiModels(listOf(rev("big", oversized), rev("small", "b")), null, null, emptyMap())
                }
            models.map { it.revision.revisionId } shouldBe listOf("big", "small")
            models.map { it.preview } shouldBe
                listOf(
                    MemoVersionPreview.Unavailable("render_document_too_large"),
                    MemoVersionPreview.Rendered(com.lomo.app.testing.fakes.emptyRenderDocument("b")),
                )
        }
    }
}

private fun rev(id: String, content: String): MemoRevision = MemoRevision(
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
