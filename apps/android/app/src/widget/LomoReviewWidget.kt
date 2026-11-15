package com.lomo.app.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lomo.app.R
import com.lomo.app.WidgetDestination
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Daily memory flashback home-screen widget. Cycles through the snapshot's curated review memos
 * per home-screen widget instance without invoking the app engine in `:widget`.
 */
class LomoReviewWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override val sizeMode: SizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(220.dp, 130.dp),
                DpSize(270.dp, 180.dp),
                DpSize(310.dp, 240.dp),
            ),
        )

    override val previewSizeMode: PreviewSizeMode =
        SizeMode.Responsive(setOf(DpSize(REVIEW_PREVIEW_WIDTH_DP.dp, REVIEW_PREVIEW_HEIGHT_DP.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = readWidgetRenderSnapshot(context)
        val widgetKey = (resolveAppWidgetId(id) ?: id).toString()
        val cursor =
            WidgetReviewCursorStore(
                file = context.filesDir.resolve(WIDGET_REVIEW_CURSOR_FILE_NAME),
            ).readCursor(widgetKey)
        val selection = selectWidgetReviewItem(snapshot.reviews, cursor)
        val today =
            snapshot.stats?.let { stats -> LocalDate.ofEpochDay(stats.asOfEpochDay) }
                ?: LocalDate.now()

        provideContent {
            GlanceTheme {
                ReviewWidgetContent(
                    context = context,
                    snapshot = snapshot,
                    selection = selection,
                    today = today,
                )
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val snapshot = buildSampleWidgetRenderSnapshot(context)
        val selection = selectWidgetReviewItem(snapshot.reviews, cursorIndex = 0)
        val today =
            snapshot.stats?.let { stats -> LocalDate.ofEpochDay(stats.asOfEpochDay) }
                ?: LocalDate.now()
        provideContent {
            GlanceTheme {
                ReviewWidgetContent(
                    context = context,
                    snapshot = snapshot,
                    selection = selection,
                    today = today,
                )
            }
        }
    }
}

class LomoReviewWidgetReceiver : AppWidgetProvider() {
    private val dispatcher =
        LomoProjectionWidgetDispatcher(LomoReviewWidgetReceiver::class.java, LomoReviewWidget())

    override fun onReceive(context: Context, intent: Intent) {
        dispatcher.onReceive(this, context, intent) { super.onReceive(context, intent) }
    }
}

class CycleWidgetReviewAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val snapshot = readWidgetRenderSnapshot(context)
        if (snapshot.availability != WidgetSnapshotAvailability.READY || snapshot.reviews.isEmpty()) {
            return
        }
        val appWidgetId = resolveAppWidgetId(glanceId) ?: return
        WidgetReviewCursorStore(
            file = context.filesDir.resolve(WIDGET_REVIEW_CURSOR_FILE_NAME),
        ).advanceCursor(widgetKey = appWidgetId.toString(), totalCount = snapshot.reviews.size)
        WidgetUpdater.updateReviewWidget(context = context, appWidgetId = appWidgetId)
    }
}

@Composable
private fun ReviewWidgetContent(
    context: Context,
    snapshot: WidgetRenderSnapshot,
    selection: WidgetReviewSelection,
    today: LocalDate,
) {
    val openReviewDestination =
        widgetLaunchAction(context, WidgetLaunchTarget.OpenDestination(WidgetDestination.DAILY_REVIEW))
    val titleAction =
        if (snapshot.availability == WidgetSnapshotAvailability.READY) {
            openReviewDestination
        } else {
            widgetLaunchAction(context, WidgetLaunchTarget.DefaultMain)
        }
    val positionBadge =
        if (snapshot.availability == WidgetSnapshotAvailability.READY && selection.total > 0) {
            "${selection.index + 1}/${selection.total}"
        } else {
            null
        }

    WidgetShell {
        WidgetHeaderRow(
            context = context,
            iconRes = R.drawable.ic_widget_sparkle,
            titleRes = R.string.widget_review_title,
            titleClick = titleAction,
            badgeText = positionBadge,
            trailing = {
                if (snapshot.availability == WidgetSnapshotAvailability.READY && selection.total > 1) {
                    WidgetActionIconButton(
                        iconRes = R.drawable.ic_widget_refresh,
                        contentDescription = context.getString(R.string.widget_review_next),
                        onClick = actionRunCallback<CycleWidgetReviewAction>(actionParametersOf()),
                        sizeDp = 30.dp,
                        iconSizeDp = 16.dp,
                    )
                }
            },
        )
        Spacer(GlanceModifier.height(10.dp))
        ReviewWidgetBody(
            context = context,
            snapshot = snapshot,
            selection = selection,
            today = today,
            openReviewDestination = openReviewDestination,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        )
    }
}

@Composable
private fun ReviewWidgetBody(
    context: Context,
    snapshot: WidgetRenderSnapshot,
    selection: WidgetReviewSelection,
    today: LocalDate,
    openReviewDestination: androidx.glance.action.Action,
    modifier: GlanceModifier = GlanceModifier,
) {
    val size = LocalSize.current
    val maxQuoteLines =
        when {
            size.height.value.roundToInt() < REVIEW_COMPACT_HEIGHT_DP -> REVIEW_COMPACT_QUOTE_LINES
            size.height.value.roundToInt() < REVIEW_TALL_HEIGHT_DP -> REVIEW_MEDIUM_QUOTE_LINES
            else -> REVIEW_TALL_QUOTE_LINES
        }

    Box(modifier = modifier) {
        when (snapshot.availability) {
            WidgetSnapshotAvailability.UNAVAILABLE ->
                WidgetNoticeCard(
                    context = context,
                    iconRes = R.drawable.ic_widget_sparkle,
                    message = context.getString(R.string.widget_snapshot_unavailable),
                )

            WidgetSnapshotAvailability.REDACTED ->
                WidgetNoticeCard(
                    context = context,
                    iconRes = R.drawable.ic_widget_lock,
                    message = context.getString(R.string.widget_review_redacted),
                )

            WidgetSnapshotAvailability.READY -> {
                val item = selection.item
                if (item == null) {
                    WidgetNoticeCard(
                        context = context,
                        iconRes = R.drawable.ic_widget_sparkle,
                        message = context.getString(R.string.widget_review_empty),
                        onClick = widgetLaunchAction(context, WidgetLaunchTarget.CreateMemo),
                    )
                } else {
                    val ageLabel = formatWidgetDateAgeLabel(context = context, dateKey = item.dateKey, today = today)
                    Column(
                        modifier =
                        GlanceModifier
                            .fillMaxSize()
                            .background(WidgetLayeredCardSurface)
                            .cornerRadius(WidgetCardCornerRadius)
                            .clickable(widgetLaunchAction(context, WidgetLaunchTarget.OpenMemo(item.id)))
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WidgetPillLabel(
                                text = ageLabel,
                                background = GlanceTheme.colors.primaryContainer,
                                textColor = GlanceTheme.colors.onPrimaryContainer,
                            )
                            item.tags.take(REVIEW_MAX_TAGS).forEach { tag ->
                                Spacer(GlanceModifier.width(5.dp))
                                WidgetPillLabel(text = "#$tag")
                            }
                        }
                        Spacer(GlanceModifier.height(8.dp))
                        Text(
                            text = item.previewText,
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                            maxLines = maxQuoteLines,
                            style =
                            TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = 13.sp,
                            ),
                        )
                        Spacer(GlanceModifier.height(8.dp))
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.dateKey,
                                maxLines = 1,
                                style =
                                TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    fontSize = 11.sp,
                                ),
                            )
                            Spacer(GlanceModifier.defaultWeight())
                            WidgetPillLabel(
                                text = context.getString(R.string.widget_review_open_all),
                                background = GlanceTheme.colors.secondaryContainer,
                                textColor = GlanceTheme.colors.onSecondaryContainer,
                                onClick = openReviewDestination,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val REVIEW_MAX_TAGS = 2
private const val REVIEW_COMPACT_HEIGHT_DP = 155
private const val REVIEW_TALL_HEIGHT_DP = 215
private const val REVIEW_COMPACT_QUOTE_LINES = 2
private const val REVIEW_MEDIUM_QUOTE_LINES = 4
private const val REVIEW_TALL_QUOTE_LINES = 6
private const val REVIEW_PREVIEW_WIDTH_DP = 270
private const val REVIEW_PREVIEW_HEIGHT_DP = 180
