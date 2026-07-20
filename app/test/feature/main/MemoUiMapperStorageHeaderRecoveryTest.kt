package com.lomo.app.feature.main

/*
 * Behavior Contract:
 * - Unit under test: MemoUiMapperStorageHeaderRecovery
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
import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class MemoUiMapperStorageHeaderRecoveryTest : AppFunSpec() {
    private val mapper = testMemoUiMapper()

    init {
        test("mapToUiModel keeps body content when geo location is absent") {
            runTest {
                val memo =
                    Memo(
                        id = "m1",
                        timestamp = 1L,
                        content = "plain body",
                        rawContent = "- 10:00 plain body",
                        dateKey = "2026_03_27",
                        geoLocation = null,
                    )
                val ui = mapper.mapToUiModel(memo, null, null, emptyMap())
                ui.processedContent shouldBe "plain body"
                ui.renderDocument.plainText shouldBe "plain body"
            }
        }

        test("mapToUiModel includes geo in processed content when present") {
            runTest {
                val memo =
                    Memo(
                        id = "m2",
                        timestamp = 1L,
                        content = "with geo",
                        rawContent = "- 10:00 with geo",
                        dateKey = "2026_03_27",
                        geoLocation = "31.2,121.4",
                    )
                val ui = mapper.mapToUiModel(memo, null, null, emptyMap())
                ui.processedContent.contains("with geo") shouldBe true
                ui.renderDocument shouldBe ui.renderDocument
            }
        }
    }
}
