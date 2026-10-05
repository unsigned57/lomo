package com.lomo.app.feature.main

/*
 * Behavior Contract:
 * - Unit under test: MemoUiMapper
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: present the memo body verbatim in the card pipeline.
 *
 * Scenarios:
 * - Given a memo body, when it is mapped to a card model, then the rendered text equals the
 *   carried body with no decoration.
 *
 * Observable outcomes:
 * - processedContent and renderDocument.plainText match the memo body.
 *
 * TDD proof:
 * - RED before the fix because the mapper appended a legacy geo URI to the display content.
 *
 * Excludes:
 * - image resolution and presentation-plan details.
 * Test Change Justification:
 * - Reason category: production API signature changed.
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
        test("mapToUiModel keeps the body content verbatim") {
            runTest {
                val memo =
                    Memo(
                        id = "m1",
                        timestamp = 1L,
                        content = "plain body",
                        rawContent = "- 10:00 plain body",
                        dateKey = "2026_03_27",
                    )
                val ui = mapper.mapToUiModel(memo, null, null, emptyMap())
                ui.processedContent shouldBe "plain body"
                ui.renderDocument.plainText shouldBe "plain body"
            }
        }
    }
}
