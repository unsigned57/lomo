package com.lomo.app.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lomo.app.R
import com.lomo.app.WidgetDestination
import kotlin.math.roundToInt

internal data class InsightsWidgetLayout(val showHeatmap: Boolean, val heatmapWeeks: Int)

internal fun resolveInsightsWidgetLayout(widthDp: Int, heightDp: Int): InsightsWidgetLayout = when {
    widthDp < INSIGHTS_HEATMAP_MIN_WIDTH_DP || heightDp < INSIGHTS_HEATMAP_MIN_HEIGHT_DP ->
        InsightsWidgetLayout(showHeatmap = false, heatmapWeeks = 0)

    widthDp < INSIGHTS_WIDE_HEATMAP_MIN_WIDTH_DP ->
        InsightsWidgetLayout(showHeatmap = true, heatmapWeeks = INSIGHTS_MEDIUM_WEEKS)

    else ->
        InsightsWidgetLayout(showHeatmap = true, heatmapWeeks = INSIGHTS_WIDE_WEEKS)
}

/**
 * Activity heatmap and writing-streak insights widget. Runs in `:widget` over snapshot data and
 * opens the full in-app Statistics destination on tap.
 */
class LomoInsightsWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override val sizeMode: SizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(180.dp, 160.dp),
                DpSize(250.dp, 150.dp),
                DpSize(320.dp, 165.dp),
            ),
        )

    override val previewSizeMode: PreviewSizeMode =
        SizeMode.Responsive(setOf(DpSize(INSIGHTS_PREVIEW_WIDTH_DP.dp, INSIGHTS_PREVIEW_HEIGHT_DP.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = readWidgetRenderSnapshot(context)
        provideContent {
            GlanceTheme {
                InsightsWidgetContent(context = context, snapshot = snapshot)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val snapshot = buildSampleWidgetRenderSnapshot(context)
        provideContent {
            GlanceTheme {
                InsightsWidgetContent(context = context, snapshot = snapshot)
            }
        }
    }
}

class LomoInsightsWidgetReceiver : AppWidgetProvider() {
    private val dispatcher =
        LomoProjectionWidgetDispatcher(LomoInsightsWidgetReceiver::class.java, LomoInsightsWidget())

    override fun onReceive(context: Context, intent: Intent) {
        dispatcher.onReceive(this, context, intent) { super.onReceive(context, intent) }
    }
}

@Composable
private fun InsightsWidgetContent(context: Context, snapshot: WidgetRenderSnapshot) {
    val size = LocalSize.current
    val layout =
        resolveInsightsWidgetLayout(
            widthDp = size.width.value.roundToInt(),
            heightDp = size.height.value.roundToInt(),
        )
    val openStatisticsAction =
        widgetLaunchAction(context, WidgetLaunchTarget.OpenDestination(WidgetDestination.STATISTICS))
    val stats = snapshot.stats
    val streakBadge =
        if (snapshot.availability == WidgetSnapshotAvailability.READY && stats != null) {
            context.getString(R.string.widget_insights_streak_days, stats.currentStreak)
        } else {
            null
        }
    val rootClick =
        if (snapshot.availability == WidgetSnapshotAvailability.READY) {
            openStatisticsAction
        } else {
            widgetLaunchAction(context, WidgetLaunchTarget.DefaultMain)
        }

    WidgetShell(backgroundClick = rootClick) {
        WidgetHeaderRow(
            context = context,
            iconRes = R.drawable.ic_widget_flame,
            titleRes = R.string.widget_insights_title,
            titleClick = rootClick,
            badgeText = streakBadge,
            trailing = {
                if (layout.showHeatmap && snapshot.availability == WidgetSnapshotAvailability.READY && stats != null) {
                    Text(
                        text =
                        context.resources.getQuantityString(
                            R.plurals.widget_insights_heatmap_range,
                            layout.heatmapWeeks,
                            layout.heatmapWeeks,
                        ),
                        maxLines = 1,
                        style =
                        TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 11.sp,
                        ),
                    )
                }
            },
        )
        Spacer(GlanceModifier.height(10.dp))
        InsightsWidgetBody(
            context = context,
            snapshot = snapshot,
            layout = layout,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        )
    }
}

@Composable
private fun InsightsWidgetBody(
    context: Context,
    snapshot: WidgetRenderSnapshot,
    layout: InsightsWidgetLayout,
    modifier: GlanceModifier = GlanceModifier,
) {
    Box(modifier = modifier) {
        when (snapshot.availability) {
            WidgetSnapshotAvailability.UNAVAILABLE ->
                WidgetNoticeCard(
                    context = context,
                    iconRes = R.drawable.ic_widget_flame,
                    message = context.getString(R.string.widget_snapshot_unavailable),
                )

            WidgetSnapshotAvailability.REDACTED -> {
                val dash = context.getString(R.string.widget_insights_redacted_value)
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        InsightsMetricTile(
                            context = context,
                            value = dash,
                            labelRes = R.string.widget_insights_total_label,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        InsightsMetricTile(
                            context = context,
                            value = dash,
                            labelRes = R.string.widget_insights_week_label,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        InsightsMetricTile(
                            context = context,
                            value = dash,
                            labelRes = R.string.widget_insights_days_label,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        WidgetNoticeCard(
                            context = context,
                            iconRes = R.drawable.ic_widget_lock,
                            message = context.getString(R.string.widget_redacted_summary),
                        )
                    }
                }
            }

            WidgetSnapshotAvailability.READY -> {
                val stats = snapshot.stats
                if (stats == null) {
                    WidgetNoticeCard(
                        context = context,
                        iconRes = R.drawable.ic_widget_flame,
                        message = context.getString(R.string.widget_snapshot_unavailable),
                    )
                } else if (!layout.showHeatmap) {
                    CompactInsightsGrid(
                        context = context,
                        stats = stats,
                        modifier = GlanceModifier.fillMaxSize(),
                    )
                } else {
                    WideInsightsHeatmap(
                        context = context,
                        stats = stats,
                        weeks = layout.heatmapWeeks,
                        modifier = GlanceModifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactInsightsGrid(
    context: Context,
    stats: WidgetGlanceStats,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(modifier = modifier) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            InsightsMetricTile(
                context = context,
                value = stats.totalMemos.toString(),
                labelRes = R.string.widget_insights_total_label,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            InsightsMetricTile(
                context = context,
                value = stats.thisWeekCount.toString(),
                labelRes = R.string.widget_insights_week_label,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            InsightsMetricTile(
                context = context,
                value = stats.activeDays.toString(),
                labelRes = R.string.widget_insights_days_label,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
    }
}

@Composable
private fun WideInsightsHeatmap(
    context: Context,
    stats: WidgetGlanceStats,
    weeks: Int,
    modifier: GlanceModifier = GlanceModifier,
) {
    val heatmapBitmap = renderWidgetHeatmapBitmap(context = context, stats = stats, weeks = weeks)
    Column(modifier = modifier) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            InsightsMetricTile(
                context = context,
                value = stats.totalMemos.toString(),
                labelRes = R.string.widget_insights_total_label,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            InsightsMetricTile(
                context = context,
                value = stats.thisWeekCount.toString(),
                labelRes = R.string.widget_insights_week_label,
                modifier = GlanceModifier.defaultWeight(),
            )
            Spacer(GlanceModifier.width(6.dp))
            InsightsMetricTile(
                context = context,
                value = stats.activeDays.toString(),
                labelRes = R.string.widget_insights_days_label,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        Box(
            modifier =
            GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
                .background(WidgetLayeredCardSurface)
                .cornerRadius(WidgetCardCornerRadius)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(heatmapBitmap),
                contentDescription = context.getString(R.string.widget_insights_heatmap_description),
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun InsightsMetricTile(
    context: Context,
    value: String,
    @StringRes labelRes: Int,
    modifier: GlanceModifier = GlanceModifier,
) {
    Column(
        modifier =
        modifier
            .background(WidgetLayeredCardSurface)
            .cornerRadius(12.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = value,
            maxLines = 1,
            style =
            TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = context.getString(labelRes),
            maxLines = 1,
            style =
            TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
            ),
        )
    }
}

private const val INSIGHTS_HEATMAP_MIN_WIDTH_DP = 220
private const val INSIGHTS_HEATMAP_MIN_HEIGHT_DP = 110
private const val INSIGHTS_WIDE_HEATMAP_MIN_WIDTH_DP = 290
private const val INSIGHTS_MEDIUM_WEEKS = 12
private const val INSIGHTS_WIDE_WEEKS = 16
private const val INSIGHTS_PREVIEW_WIDTH_DP = 250
private const val INSIGHTS_PREVIEW_HEIGHT_DP = 150
