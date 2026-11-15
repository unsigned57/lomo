package com.lomo.app.widget

import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.lomo.app.R

/**
 * Interactive Markdown checklist home-screen widget. Runs in `:widget` with read-only snapshot
 * rendering; checkbox mutations route through [ToggleWidgetTaskAction].
 */
class LomoTasksWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override val sizeMode: SizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(180.dp, 120.dp),
                DpSize(180.dp, 240.dp),
                DpSize(250.dp, 180.dp),
                DpSize(280.dp, 260.dp),
            ),
        )

    override val previewSizeMode: PreviewSizeMode =
        SizeMode.Responsive(setOf(DpSize(TASKS_PREVIEW_WIDTH_DP.dp, TASKS_PREVIEW_HEIGHT_DP.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = readWidgetRenderSnapshot(context)
        provideContent {
            GlanceTheme {
                TasksWidgetContent(context = context, snapshot = snapshot)
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val snapshot = buildSampleWidgetRenderSnapshot(context)
        provideContent {
            GlanceTheme {
                TasksWidgetContent(
                    context = context,
                    snapshot = snapshot,
                    isPreview = true,
                )
            }
        }
    }
}

class LomoTasksWidgetReceiver : AppWidgetProvider() {
    private val dispatcher =
        LomoProjectionWidgetDispatcher(LomoTasksWidgetReceiver::class.java, LomoTasksWidget())

    override fun onReceive(context: Context, intent: Intent) {
        dispatcher.onReceive(this, context, intent) { super.onReceive(context, intent) }
    }
}

@Composable
private fun TasksWidgetContent(context: Context, snapshot: WidgetRenderSnapshot, isPreview: Boolean = false) {
    val openCount = snapshot.tasks.count { !it.done }
    val badgeText =
        if (snapshot.availability == WidgetSnapshotAvailability.READY && snapshot.tasks.isNotEmpty()) {
            openCount.toString()
        } else {
            null
        }

    WidgetShell {
        WidgetHeaderRow(
            context = context,
            iconRes = R.drawable.ic_widget_check_box,
            titleRes = R.string.widget_tasks_title,
            titleClick = widgetLaunchAction(context, WidgetLaunchTarget.DefaultMain),
            badgeText = badgeText,
        ) {
            WidgetActionIconButton(
                iconRes = R.drawable.ic_widget_plus,
                contentDescription = context.getString(R.string.widget_action_new_memo),
                onClick = widgetLaunchAction(context, WidgetLaunchTarget.CreateMemo),
                containerColor = GlanceTheme.colors.primaryContainer,
                iconTint = GlanceTheme.colors.onPrimaryContainer,
                sizeDp = 30.dp,
                iconSizeDp = 16.dp,
            )
        }
        Spacer(GlanceModifier.height(10.dp))
        TasksWidgetBody(
            context = context,
            snapshot = snapshot,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            isPreview = isPreview,
        )
    }
}

@Composable
private fun TasksWidgetBody(
    context: Context,
    snapshot: WidgetRenderSnapshot,
    modifier: GlanceModifier = GlanceModifier,
    isPreview: Boolean = false,
) {
    Box(modifier = modifier) {
        when (snapshot.availability) {
            WidgetSnapshotAvailability.UNAVAILABLE ->
                WidgetNoticeCard(
                    context = context,
                    iconRes = R.drawable.ic_widget_check_box_outline,
                    message = context.getString(R.string.widget_snapshot_unavailable),
                )

            WidgetSnapshotAvailability.REDACTED ->
                WidgetNoticeCard(
                    context = context,
                    iconRes = R.drawable.ic_widget_lock,
                    message = context.getString(R.string.widget_tasks_redacted),
                )

            WidgetSnapshotAvailability.READY -> {
                if (snapshot.tasks.isEmpty()) {
                    WidgetNoticeCard(
                        context = context,
                        iconRes = R.drawable.ic_widget_check_box_outline,
                        message = context.getString(R.string.widget_tasks_empty),
                        onClick = widgetLaunchAction(context, WidgetLaunchTarget.CreateMemo),
                    )
                } else {
                    Column(
                        modifier =
                        GlanceModifier
                            .fillMaxSize()
                            .background(WidgetLayeredCardSurface)
                            .cornerRadius(WidgetCardCornerRadius)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (isPreview) {
                            Column(modifier = GlanceModifier.fillMaxSize()) {
                                snapshot.tasks.forEachIndexed { index, task ->
                                    TaskWidgetRow(context = context, task = task)
                                    if (index < snapshot.tasks.lastIndex) {
                                        Spacer(GlanceModifier.height(4.dp))
                                    }
                                }
                            }
                        } else {
                            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                                itemsIndexed(
                                    items = snapshot.tasks,
                                    itemId = { _, task -> stableWidgetListItemId("${task.memoId}:${task.lineIndex}") },
                                ) { index, task ->
                                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                                        TaskWidgetRow(context = context, task = task)
                                        if (index < snapshot.tasks.lastIndex) {
                                            Spacer(GlanceModifier.height(4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskWidgetRow(context: Context, task: WidgetGlanceTaskItem) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
            GlanceModifier
                .size(28.dp)
                .cornerRadius(8.dp)
                .clickable(
                    actionRunCallback<ToggleWidgetTaskAction>(
                        actionParametersOf(
                            WIDGET_TASK_MEMO_ID_KEY to task.memoId,
                            WIDGET_TASK_LINE_INDEX_KEY to task.lineIndex,
                            WIDGET_TASK_TARGET_DONE_KEY to !task.done,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider =
                ImageProvider(
                    if (task.done) R.drawable.ic_widget_check_box else R.drawable.ic_widget_check_box_outline,
                ),
                contentDescription = context.getString(R.string.widget_tasks_toggle_description),
                modifier = GlanceModifier.size(18.dp),
                colorFilter =
                ColorFilter.tint(
                    if (task.done) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                ),
            )
        }
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = task.text,
            modifier =
            GlanceModifier
                .defaultWeight()
                .clickable(widgetLaunchAction(context, WidgetLaunchTarget.OpenMemo(task.memoId))),
            maxLines = 1,
            style =
            TextStyle(
                color =
                if (task.done) {
                    GlanceTheme.colors.onSurfaceVariant
                } else {
                    GlanceTheme.colors.onSurface
                },
                fontSize = 13.sp,
                textDecoration =
                if (task.done) TextDecoration.LineThrough else TextDecoration.None,
            ),
        )
    }
}

private const val TASKS_PREVIEW_WIDTH_DP = 250
private const val TASKS_PREVIEW_HEIGHT_DP = 180
