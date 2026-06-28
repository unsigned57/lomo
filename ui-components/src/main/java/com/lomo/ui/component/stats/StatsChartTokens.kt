package com.lomo.ui.component.stats

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing

internal object StatsChartTokens {
    val TagBarHeight = 20.dp
    val CalendarCellSize = 12.dp
    val CalendarCellSpacing = AppSpacing.ExtraSmall
    val CalendarMonthLabelHeight = 14.dp
    val CellCornerRadius = 2.dp
    val PopupShape = AppShapes.Small
    val PopupElevation = 3.dp
    val PopupMargin = AppSpacing.ExtraSmall
    val PopupHorizontalPadding = 12.dp
    val PopupVerticalPadding = AppSpacing.Small
    val PopupTextSpacing = 2.dp
    val PopupAnchorMargin = AppSpacing.Small
    val MonthLabelMinimumSpacing = 6.dp
    val MonthLabelBaselineInset = AppSpacing.ExtraSmall
    val SelectionStrokeWidth = 2.dp
    val HourlyBarSpacing = 3.dp
    val LabelFontSize = 9.sp
    val HourlyChartAreaHeight = 100.dp
    val WeeklyCellSize = 10.dp
    val WeeklyCellSpacing = 3.dp

    private const val EmptyAlpha = 0.5f
    private const val LevelThreeAlpha = 0.7f
    private const val TagBarBaseAlpha = 0.3f
    private const val TagBarAlphaRange = 0.7f

    fun heatmapColors(colorScheme: ColorScheme): HeatmapColors =
        HeatmapColors(
            empty = colorScheme.surfaceVariant.copy(alpha = EmptyAlpha),
            level1 = colorScheme.primaryContainer.copy(alpha = EmptyAlpha),
            level2 = colorScheme.primaryContainer,
            level3 = colorScheme.primary.copy(alpha = LevelThreeAlpha),
            level4 = colorScheme.primary,
        )

    fun barChartColors(colorScheme: ColorScheme): BarChartColors =
        BarChartColors(
            empty = colorScheme.surfaceVariant.copy(alpha = EmptyAlpha),
            level1 = colorScheme.primaryContainer.copy(alpha = EmptyAlpha),
            level2 = colorScheme.primaryContainer,
            level3 = colorScheme.primary.copy(alpha = LevelThreeAlpha),
            level4 = colorScheme.primary,
        )

    fun weeklyHeatmapColors(colorScheme: ColorScheme): WeeklyHeatmapColors =
        WeeklyHeatmapColors(
            empty = colorScheme.surfaceVariant.copy(alpha = EmptyAlpha),
            level1 = colorScheme.primaryContainer.copy(alpha = EmptyAlpha),
            level2 = colorScheme.primaryContainer,
            level3 = colorScheme.primary.copy(alpha = LevelThreeAlpha),
            level4 = colorScheme.primary,
        )

    fun tagBarColor(
        baseColor: Color,
        fraction: Float,
    ): Color =
        baseColor.copy(alpha = TagBarBaseAlpha + TagBarAlphaRange * fraction)
}
