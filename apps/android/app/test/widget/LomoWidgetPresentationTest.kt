/*
 * Behavior Contract:
 * - Unit under test: LomoWidget memo row presentation.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: prepare widget memo rows with an explicit wall-clock snapshot
 *   so relative time rendering is deterministic for the whole widget pass.
 *
 * Scenarios:
 * - Given recent memos and an explicit current time, when widget row
 *   presentations are built, then every row carries that current time instead
 *   of reading wall-clock time during row rendering.
 * - Given memo content is longer than the widget preview budget, when the row
 *   presentation is built, then preview text is truncated with an ellipsis.
 *
 * Observable outcomes:
 * - WidgetMemoItemPresentation timestamp, nowMillis, and previewText values.
 *
 * TDD proof:
 * - RED before implementation because `resolveWidgetMemoItemPresentation` and
 *   `WidgetMemoItemPresentation` do not exist.
 *
 * Excludes:
 * - Glance rendering, Android DateUtils formatting, widget update scheduling,
 *   and repository fetching.
 */
package com.lomo.app.widget

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe

class LomoWidgetPresentationTest : AppFunSpec() {
    init {
        test("given memo and explicit now when presentation resolved then row carries the supplied now") {
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 1_000L,
                    content = "plain memo",
                    rawContent = "plain memo",
                    dateKey = "2026-05-23",
                )

            val presentation =
                resolveWidgetMemoItemPresentation(
                    memo = memo,
                    nowMillis = 5_000L,
                )

            presentation shouldBe
                WidgetMemoItemPresentation(
                    id = "memo-1",
                    timestampMillis = 1_000L,
                    nowMillis = 5_000L,
                    previewText = "plain memo",
                )
        }

        test("given long memo content when presentation resolved then preview is truncated") {
            val content = "a".repeat(101)
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 1_000L,
                    content = content,
                    rawContent = content,
                    dateKey = "2026-05-23",
                )

            val presentation =
                resolveWidgetMemoItemPresentation(
                    memo = memo,
                    nowMillis = 5_000L,
                )

            presentation.previewText shouldBe "${"a".repeat(100)}..."
        }
    }
}
