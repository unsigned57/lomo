/*
 * Behavior Contract:
 * - Unit under test: WidgetReviewCursorStore, selectWidgetReviewItem, buildWidgetHeatmapGrid,
 *   and responsive layout mode selectors for QuickNotes / Insights / Tasks widgets.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: maintain deterministic cursor rotation across home-screen review widgets, build
 *   calendar-aligned heatmap cells respecting thresholds and future-day masking, and select
 *   non-overlapping responsive widget layouts across host dimensions.
 *
 * Scenarios:
 * - Given empty or multi-item review list, when selecting active review, then index wraps safely
 *   without throwing and formats badge counts.
 * - Given WidgetReviewCursorStore, when advancing cursors for distinct widget keys, then cursor
 *   persists atomically across reads.
 * - Given WidgetGlanceStats with counts spanning thresholds and future days in the trailing week,
 *   when building the heatmap grid, then cells align to today with future cells marked hidden and
 *   intensities matched to thresholds.
 * - Given compact vs expanded widget sizes, when resolving layouts, then row budgets and heatmap
 *   visibility prevent clipping.
 *
 * Observable outcomes: cursor persistence, grid cell coordinates/intensities, layout modes.
 * TDD proof:
 * - RED before WidgetReviewCursorStore, WidgetHeatmapBitmapRenderer, and widget layout policies exist.
 * Excludes: Android Canvas rasterization pixels and Glance RemoteViews rendering.
 */
package com.lomo.app.widget

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlin.io.path.createTempDirectory

class WidgetReviewAndHeatmapPolicyTest : AppFunSpec() {
    init {
        test("WidgetReviewCursorStore persists and wraps per-widget review cursor atomically") {
            val dir = createTempDirectory("widget-review-cursor").toFile()
            try {
                val store = WidgetReviewCursorStore(dir.resolve(WIDGET_REVIEW_CURSOR_FILE_NAME))
                store.readCursor("w-1") shouldBe 0
                store.advanceCursor("w-1", totalCount = 3) shouldBe 1
                store.advanceCursor("w-1", totalCount = 3) shouldBe 2
                store.advanceCursor("w-1", totalCount = 3) shouldBe 0
                store.readCursor("w-1") shouldBe 0
            } finally {
                dir.deleteRecursively()
            }
        }

        test("selectWidgetReviewItem wraps safely and handles empty review lists") {
            selectWidgetReviewItem(emptyList(), cursorIndex = 2) shouldBe WidgetReviewSelection(null, 0, 0)

            val reviews =
                listOf(
                    WidgetGlanceReviewItem("r1", 1_000L, "First memory", "2026-01-01"),
                    WidgetGlanceReviewItem("r2", 2_000L, "Second memory", "2026-02-02"),
                )
            selectWidgetReviewItem(reviews, cursorIndex = 3) shouldBe WidgetReviewSelection(reviews[1], 1, 2)
        }

        test("buildWidgetHeatmapGrid aligns trailing column to asOfDate and maps intensities") {
            // 2026-11-14 is Saturday (DayOfWeek = SATURDAY)
            val today = LocalDate.of(2026, 11, 14)
            val stats =
                WidgetGlanceStats(
                    totalMemos = 10,
                    todayCount = 4,
                    thisWeekCount = 5,
                    currentStreak = 2,
                    longestStreak = 5,
                    activeDays = 3,
                    asOfEpochDay = today.toEpochDay(),
                    level1Max = 1,
                    level2Max = 3,
                    level3Max = 6,
                    heatmapDays =
                    listOf(
                        WidgetGlanceHeatmapDay(today.minusDays(1).toEpochDay(), 1),
                        WidgetGlanceHeatmapDay(today.toEpochDay(), 4),
                    ),
                )

            val cells = buildWidgetHeatmapGrid(stats = stats, weeks = 14)

            cells.size shouldBe 14 * 7
            val todayCell = cells.first { it.date == today }
            todayCell.weekColumn shouldBe 13
            todayCell.intensityLevel shouldBe 3
            todayCell.isFuture shouldBe false

            val yesterdayCell = cells.first { it.date == today.minusDays(1) }
            yesterdayCell.intensityLevel shouldBe 1

            cells.filter { it.isFuture }.all { it.weekColumn == 13 } shouldBe true
        }

        test("responsive layout planners select appropriate modes across sizes") {
            resolveQuickNotesLayoutMode(widthDp = 250, heightDp = 100) shouldBe QuickNotesLayoutMode.COMPACT_BAR
            resolveQuickNotesLayoutMode(widthDp = 250, heightDp = 160) shouldBe QuickNotesLayoutMode.MEDIUM_CARD
            resolveQuickNotesLayoutMode(widthDp = 250, heightDp = 240) shouldBe QuickNotesLayoutMode.EXPANDED_FEED

            resolveInsightsWidgetLayout(widthDp = 180, heightDp = 180) shouldBe
                InsightsWidgetLayout(showHeatmap = false, heatmapWeeks = 0)
            resolveInsightsWidgetLayout(widthDp = 250, heightDp = 150) shouldBe
                InsightsWidgetLayout(showHeatmap = true, heatmapWeeks = 12)
            resolveInsightsWidgetLayout(widthDp = 320, heightDp = 160) shouldBe
                InsightsWidgetLayout(showHeatmap = true, heatmapWeeks = 16)
        }
    }
}
