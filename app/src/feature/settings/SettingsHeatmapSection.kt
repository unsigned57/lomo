package com.lomo.app.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SettingsGroup

@Composable
fun HeatmapSettingsSection(
    thresholds: CalendarHeatmapThresholds,
    onOpenThresholdDialog: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_group_heatmap)) {
        PreferenceItem(
            title = stringResource(R.string.settings_calendar_heatmap_thresholds),
            subtitle = calendarHeatmapThresholdSummary(thresholds),
            icon = Icons.Outlined.CalendarToday,
            onClick = onOpenThresholdDialog,
        )
    }
}

@Composable
internal fun calendarHeatmapThresholdSummary(thresholds: CalendarHeatmapThresholds): String {
    val separator = stringResource(R.string.settings_heatmap_range_separator)
    return calendarHeatmapThresholdRangeLabels(thresholds).joinToString(separator)
}

@Composable
internal fun calendarHeatmapThresholdRangeLabels(thresholds: CalendarHeatmapThresholds): List<String> {
    val labels = mutableListOf<String>()
    for (row in calendarHeatmapThresholdRangeRows(thresholds)) {
        labels += calendarHeatmapThresholdRangeLabel(row)
    }
    return labels
}

@Composable
private fun calendarHeatmapThresholdRangeLabel(row: CalendarHeatmapThresholdRangeRow): String {
    val toneLabel = calendarHeatmapThresholdToneLabel(row.tone)
    return when (val range = row.range) {
        CalendarHeatmapThresholdCountRange.Empty ->
            stringResource(R.string.settings_heatmap_range_empty, toneLabel)
        is CalendarHeatmapThresholdCountRange.Single ->
            stringResource(R.string.settings_heatmap_range_single, range.count, toneLabel)
        is CalendarHeatmapThresholdCountRange.Bounded ->
            stringResource(R.string.settings_heatmap_range_bounded, range.start, range.endInclusive, toneLabel)
        is CalendarHeatmapThresholdCountRange.Unbounded ->
            stringResource(R.string.settings_heatmap_range_unbounded, range.start, toneLabel)
    }
}

@Composable
private fun calendarHeatmapThresholdToneLabel(tone: CalendarHeatmapThresholdTone): String =
    when (tone) {
        CalendarHeatmapThresholdTone.Blank -> stringResource(R.string.settings_heatmap_tone_blank)
        CalendarHeatmapThresholdTone.Light -> stringResource(R.string.settings_heatmap_tone_light)
        CalendarHeatmapThresholdTone.Medium -> stringResource(R.string.settings_heatmap_tone_medium)
        CalendarHeatmapThresholdTone.Darker -> stringResource(R.string.settings_heatmap_tone_darker)
        CalendarHeatmapThresholdTone.Darkest -> stringResource(R.string.settings_heatmap_tone_darkest)
    }
