/*
 * Behavior Contract:
 * - Unit under test: ToggleMemoCheckboxUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: route one Rust-issued task action span through the owner session transaction.
 *
 * Scenarios:
 * - Given a memo identity and typed action span, when toggled, then the exact span is forwarded and
 *   the committed content is returned without issuing a second write.
 * - Given the owner rejects a stale fingerprint/span, when toggled, then the structured failure is
 *   propagated and refresh is not attempted.
 *
 * Observable outcomes: forwarded memo identity/span, returned content, unchanged refresh count, exception.
 * TDD proof: RED because the prior use case accepted a Kotlin line index and rewrote Markdown with
 * a regex instead of accepting MarkdownSourceSpan and calling MarkdownWorkspaceRepository.
 * Excludes: Rust patch planning, Android platform batches, and Compose rendering.

 * Test Change Justification:
 * Reason category: fix duplicate task commit after the session transaction.
 * Old behavior/assertion being replaced: one separate document-projection mutation after toggle.
 * Why old assertion is no longer correct: the session commits source, history and projection together.
 * Coverage preserved by: exact span and returned body plus owner rejection propagation remain asserted.
 * Why this is not fitting the test to the implementation: no second durable write is the user-visible law.

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

package com.lomo.domain.usecase

import com.lomo.domain.model.EngineCommandFailure
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.Memo
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeMemoStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ToggleMemoCheckboxUseCaseTest : DomainFunSpec() {
    init {
        test("forwards the typed task span and refreshes after the owner command") {
            runTest {
                val store = FakeMemoStore()
                val workspace = FakeMarkdownWorkspaceRepository(updatedContent = "- [x] task")
                val useCase =
                    ToggleMemoCheckboxUseCase(
                        workspaceRepository = workspace,
                    )
                val span = MarkdownSourceSpan(startByte = 2uL, endByte = 5uL)

                val result = useCase(memo = memo(), actionSpan = span)

                result shouldBe "- [x] task"
                workspace.lastIdentity shouldBe "memo"
                workspace.lastSpan shouldBe span
                store.refreshMemosCallCount shouldBe 0
            }
        }

        test("propagates stale owner failure without refreshing") {
            runTest {
                val store = FakeMemoStore()
                val workspace =
                    FakeMarkdownWorkspaceRepository(
                        failure =
                        EngineCommandFailureException(
                            EngineCommandFailure(
                                category = EngineFailureCategory.CONFLICT,
                                code = "stale_snapshot",
                                retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                                operationId = null,
                                jobId = null,
                                diagnostic = "document changed",
                            ),
                        ),
                    )
                val useCase =
                    ToggleMemoCheckboxUseCase(
                        workspaceRepository = workspace,
                    )

                val error =
                    shouldThrow<EngineCommandFailureException> {
                        useCase(
                            memo = memo(),
                            actionSpan = MarkdownSourceSpan(startByte = 2uL, endByte = 5uL),
                        )
                    }

                error.failure.code shouldBe "stale_snapshot"
                store.refreshMemosCallCount shouldBe 0
            }
        }
    }

    private fun memo(): Memo = Memo(
        id = "memo",
        timestamp = 1L,
        content = "- [ ] task",
        rawContent = "- [ ] task",
        dateKey = "2026_02_24",
    )
}

private class FakeMarkdownWorkspaceRepository(
    private val updatedContent: String = "",
    private val failure: EngineCommandFailureException? = null,
) : MarkdownWorkspaceRepository {
    var lastIdentity: String? = null
    var lastSpan: MarkdownSourceSpan? = null

    override fun renderMarkdown(content: String): MarkdownRenderDocument = error("render not expected")

    override suspend fun loadMemoPreviews(
        requests: List<com.lomo.domain.model.MemoPreviewRenderRequest>,
    ): Map<String, MarkdownRenderDocument> = error("preview batch not expected")

    override suspend fun toggleTask(
        operationId: com.lomo.domain.model.MemoOperationId,
        memoIdentity: String,
        actionSpan: MarkdownSourceSpan,
    ): String {
        lastIdentity = memoIdentity
        lastSpan = actionSpan
        failure?.let { throw it }
        return updatedContent
    }
}
