package com.lomo.app.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import java.time.LocalDate

internal data class WidgetHeatmapCell(
    val weekColumn: Int,
    val dayRow: Int,
    val date: LocalDate,
    val intensityLevel: Int,
    val isFuture: Boolean,
)

internal data class WidgetHeatmapPalette(
    val empty: Int,
    val level1: Int,
    val level2: Int,
    val level3: Int,
    val level4: Int,
)

internal fun buildWidgetHeatmapGrid(stats: WidgetGlanceStats, weeks: Int): List<WidgetHeatmapCell> {
    if (weeks <= 0) return emptyList()
    val asOfDate = LocalDate.ofEpochDay(stats.asOfEpochDay)
    val countByEpochDay = stats.heatmapDays.associate { day -> day.epochDay to day.count }
    // Anchor weekColumn = (weeks - 1) on Monday of asOfDate's ISO week so trailing future days
    // remain isolated in the rightmost column.
    val currentWeekMonday = asOfDate.minusDays((asOfDate.dayOfWeek.value - 1).toLong())
    val gridStart = currentWeekMonday.minusWeeks((weeks - 1).toLong())

    return buildList(weeks * HEATMAP_DAYS_PER_WEEK) {
        for (week in 0 until weeks) {
            for (dayRow in 0 until HEATMAP_DAYS_PER_WEEK) {
                val date = gridStart.plusDays((week * HEATMAP_DAYS_PER_WEEK + dayRow).toLong())
                val isFuture = date.isAfter(asOfDate)
                val count = if (isFuture) 0 else countByEpochDay[date.toEpochDay()] ?: 0
                add(
                    WidgetHeatmapCell(
                        weekColumn = week,
                        dayRow = dayRow,
                        date = date,
                        intensityLevel = resolveIntensityLevel(count, stats),
                        isFuture = isFuture,
                    ),
                )
            }
        }
    }
}

private fun resolveIntensityLevel(count: Int, stats: WidgetGlanceStats): Int = when {
    count <= 0 -> HEATMAP_INTENSITY_EMPTY
    count <= stats.level1Max -> HEATMAP_INTENSITY_LOW
    count <= stats.level2Max -> HEATMAP_INTENSITY_MEDIUM
    count <= stats.level3Max -> HEATMAP_INTENSITY_HIGH
    else -> HEATMAP_INTENSITY_MAX
}

internal fun renderWidgetHeatmapBitmap(context: Context, stats: WidgetGlanceStats, weeks: Int): Bitmap {
    val safeWeeks = weeks.coerceAtLeast(1)
    val cells = buildWidgetHeatmapGrid(stats = stats, weeks = safeWeeks)
    val widthPx = safeWeeks * HEATMAP_CELL_STEP_PX - HEATMAP_CELL_GAP_PX
    val heightPx = HEATMAP_DAYS_PER_WEEK * HEATMAP_CELL_STEP_PX - HEATMAP_CELL_GAP_PX
    val bitmap = createBitmap(widthPx, heightPx)
    val canvas = Canvas(bitmap)
    val palette = resolveHeatmapPalette(context)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = RectF()

    for (cell in cells) {
        if (cell.isFuture) continue
        val left = (cell.weekColumn * HEATMAP_CELL_STEP_PX).toFloat()
        val top = (cell.dayRow * HEATMAP_CELL_STEP_PX).toFloat()
        rect.set(left, top, left + HEATMAP_CELL_SIZE_PX, top + HEATMAP_CELL_SIZE_PX)
        paint.color =
            when (cell.intensityLevel) {
                HEATMAP_INTENSITY_EMPTY -> palette.empty
                HEATMAP_INTENSITY_LOW -> palette.level1
                HEATMAP_INTENSITY_MEDIUM -> palette.level2
                HEATMAP_INTENSITY_HIGH -> palette.level3
                else -> palette.level4
            }
        canvas.drawRoundRect(rect, HEATMAP_CELL_RADIUS_PX, HEATMAP_CELL_RADIUS_PX, paint)
    }
    return bitmap
}

private fun resolveHeatmapPalette(context: Context): WidgetHeatmapPalette {
    val night =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return resolveDynamicHeatmapPalette(context, night)
    }
    return if (night) {
        WidgetHeatmapPalette(
            empty = 0xFF2B303B.toInt(),
            level1 = 0xFF1F335C.toInt(),
            level2 = 0xFF2A4D9C.toInt(),
            level3 = 0xFF4E7DF7.toInt(),
            level4 = 0xFF8CB1FF.toInt(),
        )
    } else {
        WidgetHeatmapPalette(
            empty = 0xFFE6EAF3.toInt(),
            level1 = 0xFFC9D9FF.toInt(),
            level2 = 0xFF8FB0FF.toInt(),
            level3 = 0xFF3E6FF6.toInt(),
            level4 = 0xFF1D4ED8.toInt(),
        )
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun resolveDynamicHeatmapPalette(context: Context, night: Boolean): WidgetHeatmapPalette = if (night) {
    WidgetHeatmapPalette(
        empty = ContextCompat.getColor(context, android.R.color.system_neutral2_800),
        level1 = ContextCompat.getColor(context, android.R.color.system_accent1_900),
        level2 = ContextCompat.getColor(context, android.R.color.system_accent1_700),
        level3 = ContextCompat.getColor(context, android.R.color.system_accent1_400),
        level4 = ContextCompat.getColor(context, android.R.color.system_accent1_200),
    )
} else {
    WidgetHeatmapPalette(
        empty = ContextCompat.getColor(context, android.R.color.system_neutral2_100),
        level1 = ContextCompat.getColor(context, android.R.color.system_accent1_100),
        level2 = ContextCompat.getColor(context, android.R.color.system_accent1_300),
        level3 = ContextCompat.getColor(context, android.R.color.system_accent1_500),
        level4 = ContextCompat.getColor(context, android.R.color.system_accent1_700),
    )
}

private const val HEATMAP_DAYS_PER_WEEK = 7
private const val HEATMAP_CELL_SIZE_PX = 20
private const val HEATMAP_CELL_GAP_PX = 5
private const val HEATMAP_CELL_STEP_PX = HEATMAP_CELL_SIZE_PX + HEATMAP_CELL_GAP_PX
private const val HEATMAP_CELL_RADIUS_PX = 5.5f
private const val HEATMAP_INTENSITY_EMPTY = 0
private const val HEATMAP_INTENSITY_LOW = 1
private const val HEATMAP_INTENSITY_MEDIUM = 2
private const val HEATMAP_INTENSITY_HIGH = 3
private const val HEATMAP_INTENSITY_MAX = 4
