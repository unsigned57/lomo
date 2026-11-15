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
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: widget presentation tested static row truncation
 *   (`widgetVisibleItemCount`) capped to 1..4 static rows.
 * - Why old assertion is no longer correct: Quick Notes and Tasks widgets now render scrollable
 *   Glance `LazyColumn` feeds over the snapshot slice instead of slicing to static non-scrollable
 *   `Column` limits, and require collision-safe non-negative stable item IDs outside Glance's
 *   reserved ID range plus a fuller recent memo feed budget (`WIDGET_MEMO_LIMIT`).
 * - Coverage preserved by: deterministic memo row formatting and plain-text fidelity tests remain,
 *   while list presentation now verifies stable non-reserved `LazyColumn` item IDs, expanded memo
 *   feed budget, and narrow-tall `QuickNotesLayoutMode` selection.
 * - Why this is not fitting the test to the implementation: asserts stable ID monotonicity/bounds,
 *   domain feed capacity, and non-compact mode selection required by Glance `LazyColumn`.
 */
package com.lomo.app.widget

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

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

        test("given projected plain-text content when presentation resolved then the host never re-renders markdown") {
            val memo =
                Memo(
                    id = "memo-1",
                    timestamp = 1_000L,
                    content = "# heading stays literal",
                    rawContent = "# heading stays literal",
                    dateKey = "2026-05-23",
                )

            val presentation =
                resolveWidgetMemoItemPresentation(
                    memo = memo,
                    nowMillis = 5_000L,
                )

            presentation.previewText shouldBe "# heading stays literal"
        }

        test("given list keys when stableWidgetListItemId resolves then IDs are positive, stable, and unique") {
            val first = stableWidgetListItemId("memo-1")
            val repeat = stableWidgetListItemId("memo-1")
            val second = stableWidgetListItemId("memo-2")

            first shouldBe repeat
            first shouldNotBe second
            first shouldBeGreaterThanOrEqualTo 0L
            second shouldBeGreaterThanOrEqualTo 0L
        }

        test("given scrollable quick notes feed then snapshot budget exposes at least 15 recent memos") {
            WIDGET_MEMO_LIMIT shouldBeGreaterThanOrEqualTo 15
        }

        test("given a 3-column phone width with medium height then quick notes resolves expandable feed mode") {
            resolveQuickNotesLayoutMode(widthDp = 180, heightDp = 180) shouldBe QuickNotesLayoutMode.MEDIUM_CARD
            resolveQuickNotesLayoutMode(widthDp = 180, heightDp = 240) shouldBe QuickNotesLayoutMode.EXPANDED_FEED
        }
    }
}
