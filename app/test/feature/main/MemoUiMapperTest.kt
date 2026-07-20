package com.lomo.app.feature.main

/*
 * Behavior Contract:
 * - Unit under test: MemoUiMapper
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

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeMarkdownWorkspaceRepository
import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

class MemoUiMapperTest : AppFunSpec() {
    private val mapper =
        testMemoUiMapper(
            workspace =
                FakeMarkdownWorkspaceRepository(
                    plainTextTransform = { content ->
                        // Presentation plain text drops known #tag tokens for body display contracts.
                        content
                            .replace(Regex("""(^|\s)#(work|todo)(?=\s|$)""")) { match ->
                                if (match.value.first().isWhitespace()) " " else ""
                            }.trim()
                    },
                ),
        )

    init {
        test("mapToUiModel projects owner IR document and keeps raw tags on processed content") {
            runTest {
                val memo =
                    memo(
                        content = "Meeting with C# team #work and #todo today.",
                        tags = listOf("work", "todo"),
                    )

                val uiModel = mapper.mapToUiModel(memo, rootPath = null, imagePath = null, imageMap = emptyMap())

                uiModel.processedContent.contains("#work") shouldBe true
                uiModel.processedContent.contains("#todo") shouldBe true
                uiModel.renderDocument.plainText.contains("C#") shouldBe true
                uiModel.renderDocument.plainText.contains("#work") shouldBe false
                uiModel.renderDocument.plainText.contains("#todo") shouldBe false
                uiModel.tags shouldBe listOf("work", "todo")
            }
        }

        test("mapToUiModel invalidates cache when memo content changes") {
            runTest {
                val first = memo(content = "first body")
                val initial = mapper.mapToUiModel(first, null, null, emptyMap())
                val updated =
                    mapper.mapToUiModel(first.copy(content = "second body"), null, null, emptyMap())

                initial.renderDocument.plainText shouldBe "first body"
                updated.renderDocument.plainText shouldBe "second body"
                (updated.renderDocument === initial.renderDocument) shouldBe false
            }
        }

        test("mapToUiModels maps multiple memos with stable ids") {
            runTest {
                val memos =
                    listOf(
                        memo(id = "a", content = "alpha"),
                        memo(id = "b", content = "beta"),
                    )
                val models = mapper.mapToUiModels(memos, null, null, emptyMap())
                models.map { it.memo.id } shouldBe listOf("a", "b")
                models.map { it.renderDocument.plainText } shouldBe listOf("alpha", "beta")
                models.first().renderDocument shouldNotBe null
            }
        }
    }
}

private fun memo(
    id: String = "memo-1",
    content: String,
    tags: List<String> = emptyList(),
): Memo =
    Memo(
        id = id,
        timestamp = 1_700_000_000_000L,
        content = content,
        rawContent = "- 10:00 $content",
        dateKey = "2026_03_27",
        tags = tags,
    )
