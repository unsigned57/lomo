package com.lomo.app.widget

import android.content.Context
import com.lomo.app.R
import java.time.LocalDate

internal const val PREVIEW_REFERENCE_NOW_MILLIS = 1_700_000_120_000L
private const val PREVIEW_MEMO_ONE_AGE_MILLIS = 120_000L
private const val PREVIEW_MEMO_TWO_AGE_MILLIS = 3_600_000L
private const val PREVIEW_REVIEW_AGE_DAYS = 30L
private const val PREVIEW_MILLIS_PER_DAY = 86_400_000L
private const val PREVIEW_STATS_TOTAL_MEMOS = 148
private const val PREVIEW_STATS_TODAY_COUNT = 3
private const val PREVIEW_STATS_WEEK_COUNT = 12
private const val PREVIEW_STATS_CURRENT_STREAK = 7
private const val PREVIEW_STATS_LONGEST_STREAK = 14
private const val PREVIEW_STATS_ACTIVE_DAYS = 46
private const val PREVIEW_STATS_LEVEL1_MAX = 1
private const val PREVIEW_STATS_LEVEL2_MAX = 3
private const val PREVIEW_STATS_LEVEL3_MAX = 5
private const val PREVIEW_HEATMAP_MODULO = 6

internal data class WidgetPreviewSampleStrings(
    val memoFirst: String,
    val memoSecond: String,
    val taskFirst: String,
    val taskSecond: String,
    val reviewQuote: String,
    val tagPrimary: String,
    val tagSecondary: String,
)

internal fun buildSampleWidgetRenderSnapshot(context: Context): WidgetRenderSnapshot {
    val today = LocalDate.now()
    return buildSampleWidgetRenderSnapshot(
        strings =
        WidgetPreviewSampleStrings(
            memoFirst = context.getString(R.string.widget_sample_memo_first),
            memoSecond = context.getString(R.string.widget_sample_memo_second),
            taskFirst = context.getString(R.string.widget_sample_task_first),
            taskSecond = context.getString(R.string.widget_sample_task_second),
            reviewQuote = context.getString(R.string.widget_sample_review_quote),
            tagPrimary = context.getString(R.string.widget_sample_tag_ideas),
            tagSecondary = context.getString(R.string.widget_sample_tag_reading),
        ),
        nowMillis = PREVIEW_REFERENCE_NOW_MILLIS,
        asOfEpochDay = today.toEpochDay(),
    )
}

internal fun buildSampleWidgetRenderSnapshot(
    strings: WidgetPreviewSampleStrings,
    nowMillis: Long,
    asOfEpochDay: Long,
): WidgetRenderSnapshot {
    val todayDateKey = LocalDate.ofEpochDay(asOfEpochDay).toString()
    val reviewDateKey = LocalDate.ofEpochDay(asOfEpochDay - PREVIEW_REVIEW_AGE_DAYS).toString()
    val firstDayOffset = WIDGET_HEATMAP_DAY_LIMIT - 1
    val heatmapDays =
        (0 until WIDGET_HEATMAP_DAY_LIMIT).map { index ->
            val epochDay = asOfEpochDay - (firstDayOffset - index)
            val count = ((index * 5) + 1) % PREVIEW_HEATMAP_MODULO
            WidgetGlanceHeatmapDay(epochDay = epochDay, count = count)
        }
    return WidgetRenderSnapshot(
        availability = WidgetSnapshotAvailability.READY,
        items =
        listOf(
            WidgetGlanceSnapshotItem(
                id = "preview_memo_1",
                timestampMillis = nowMillis - PREVIEW_MEMO_ONE_AGE_MILLIS,
                previewText = strings.memoFirst,
                tags = listOf(strings.tagPrimary),
                isPinned = true,
            ),
            WidgetGlanceSnapshotItem(
                id = "preview_memo_2",
                timestampMillis = nowMillis - PREVIEW_MEMO_TWO_AGE_MILLIS,
                previewText = strings.memoSecond,
                tags = listOf(strings.tagSecondary),
                isPinned = false,
            ),
        ),
        tasks =
        listOf(
            WidgetGlanceTaskItem(
                memoId = "preview_task_memo_1",
                lineIndex = 0,
                done = true,
                text = strings.taskFirst,
                dateKey = todayDateKey,
            ),
            WidgetGlanceTaskItem(
                memoId = "preview_task_memo_2",
                lineIndex = 1,
                done = false,
                text = strings.taskSecond,
                dateKey = todayDateKey,
            ),
        ),
        stats =
        WidgetGlanceStats(
            totalMemos = PREVIEW_STATS_TOTAL_MEMOS,
            todayCount = PREVIEW_STATS_TODAY_COUNT,
            thisWeekCount = PREVIEW_STATS_WEEK_COUNT,
            currentStreak = PREVIEW_STATS_CURRENT_STREAK,
            longestStreak = PREVIEW_STATS_LONGEST_STREAK,
            activeDays = PREVIEW_STATS_ACTIVE_DAYS,
            asOfEpochDay = asOfEpochDay,
            level1Max = PREVIEW_STATS_LEVEL1_MAX,
            level2Max = PREVIEW_STATS_LEVEL2_MAX,
            level3Max = PREVIEW_STATS_LEVEL3_MAX,
            heatmapDays = heatmapDays,
        ),
        reviews =
        listOf(
            WidgetGlanceReviewItem(
                id = "preview_review_1",
                timestampMillis = nowMillis - (PREVIEW_REVIEW_AGE_DAYS * PREVIEW_MILLIS_PER_DAY),
                previewText = strings.reviewQuote,
                dateKey = reviewDateKey,
                tags = listOf(strings.tagPrimary, strings.tagSecondary),
            ),
            WidgetGlanceReviewItem(
                id = "preview_review_2",
                timestampMillis = nowMillis - (PREVIEW_REVIEW_AGE_DAYS * 2 * PREVIEW_MILLIS_PER_DAY),
                previewText = strings.memoFirst,
                dateKey = LocalDate.ofEpochDay(asOfEpochDay - (PREVIEW_REVIEW_AGE_DAYS * 2)).toString(),
                tags = listOf(strings.tagPrimary),
            ),
        ),
    )
}
