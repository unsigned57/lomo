package com.lomo.ui.component.stats

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.materialkolor.hct.Hct

internal object StatsChartTokens {
    val TagBarHeight = 20.dp
    val CalendarCellSize = 12.dp
    val CalendarCellSpacing = 3.dp
    val CalendarMonthLabelHeight = 14.dp
    val CellCornerRadius = 3.dp
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

    private const val RampLevelOne = 0.30f
    private const val RampLevelTwo = 0.52f
    private const val RampLevelThree = 0.76f
    private const val TagBarBaseFraction = 0.3f
    private const val TagBarFractionRange = 0.7f

    /**
     * Activity ramps interpolate in HCT space from a neutral surface step toward `primary` so
     * every level is an opaque color — no alpha overlays that composite unpredictably over the
     * drawer or statistics cards in light and dark themes.
     */
    fun heatmapColors(colorScheme: ColorScheme): HeatmapColors {
        val ramp = activityRamp(colorScheme)
        return HeatmapColors(
            empty = colorScheme.surfaceContainerHighest,
            level1 = ramp(RampLevelOne),
            level2 = ramp(RampLevelTwo),
            level3 = ramp(RampLevelThree),
            level4 = colorScheme.primary,
        )
    }

    fun barChartColors(colorScheme: ColorScheme): BarChartColors {
        val ramp = activityRamp(colorScheme)
        return BarChartColors(
            empty = colorScheme.surfaceContainerHighest,
            level1 = ramp(RampLevelOne),
            level2 = ramp(RampLevelTwo),
            level3 = ramp(RampLevelThree),
            level4 = colorScheme.primary,
        )
    }

    fun weeklyHeatmapColors(colorScheme: ColorScheme): WeeklyHeatmapColors {
        val ramp = activityRamp(colorScheme)
        return WeeklyHeatmapColors(
            empty = colorScheme.surfaceContainerHighest,
            level1 = ramp(RampLevelOne),
            level2 = ramp(RampLevelTwo),
            level3 = ramp(RampLevelThree),
            level4 = colorScheme.primary,
        )
    }

    fun tagBarColor(colorScheme: ColorScheme, baseColor: Color, fraction: Float): Color = hctStep(
        start = colorScheme.surfaceContainerHighest,
        end = baseColor,
        fraction = TagBarBaseFraction + TagBarFractionRange * fraction,
    )

    private fun activityRamp(colorScheme: ColorScheme): (Float) -> Color {
        val start = colorScheme.surfaceContainerHighest
        val end = colorScheme.primary
        return { fraction -> hctStep(start, end, fraction) }
    }

    private fun hctStep(start: Color, end: Color, fraction: Float): Color {
        val startHct = Hct.fromInt(start.toArgb())
        val endHct = Hct.fromInt(end.toArgb())
        return Color(
            Hct.from(
                startHct.hue + (endHct.hue - startHct.hue) * fraction,
                startHct.chroma + (endHct.chroma - startHct.chroma) * fraction,
                startHct.tone + (endHct.tone - startHct.tone) * fraction,
            ).toInt(),
        )
    }
}
