package com.lomo.app.widget

import android.content.Context
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
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.PreviewSizeMode
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lomo.app.R
import com.lomo.app.WidgetDestination
import com.lomo.app.util.MarkdownCleanupFormatter
import com.lomo.domain.model.Memo
import kotlin.math.roundToInt

internal enum class QuickNotesLayoutMode {
    COMPACT_BAR,
    MEDIUM_CARD,
    EXPANDED_FEED,
}

internal fun resolveQuickNotesLayoutMode(widthDp: Int, heightDp: Int): QuickNotesLayoutMode = when {
    heightDp < WIDGET_COMPACT_HEIGHT_DP || widthDp < WIDGET_COMPACT_WIDTH_DP ->
        QuickNotesLayoutMode.COMPACT_BAR

    heightDp < WIDGET_EXPANDED_HEIGHT_DP -> QuickNotesLayoutMode.MEDIUM_CARD

    else -> QuickNotesLayoutMode.EXPANDED_FEED
}

/**
 * Quick capture and recent notes Glance widget. Runs in `:widget`, uses read-only snapshot state
 * (`stateDefinition = null`), and mints trusted launch intents at tap time via [WidgetLaunchActivity].
 */
class LomoWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override val sizeMode: SizeMode =
        SizeMode.Responsive(
            setOf(
                DpSize(180.dp, 90.dp),
                DpSize(180.dp, 180.dp),
                DpSize(250.dp, 160.dp),
                DpSize(280.dp, 250.dp),
            ),
        )

    override val previewSizeMode: PreviewSizeMode =
        SizeMode.Responsive(setOf(DpSize(PREVIEW_WIDTH_DP.dp, PREVIEW_HEIGHT_DP.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = readWidgetRenderSnapshot(context)
        val nowMillis = System.currentTimeMillis()
        provideContent {
            GlanceTheme {
                QuickNotesWidgetContent(
                    context = context,
                    snapshot = snapshot,
                    nowMillis = nowMillis,
                )
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val snapshot = buildSampleWidgetRenderSnapshot(context)
        provideContent {
            GlanceTheme {
                QuickNotesWidgetContent(
                    context = context,
                    snapshot = snapshot,
                    nowMillis = PREVIEW_REFERENCE_NOW_MILLIS,
                    isPreview = true,
                )
            }
        }
    }
}

@Composable
private fun QuickNotesWidgetContent(
    context: Context,
    snapshot: WidgetRenderSnapshot,
    nowMillis: Long,
    isPreview: Boolean = false,
) {
    val size = LocalSize.current
    val widthDp = size.width.value.roundToInt()
    val heightDp = size.height.value.roundToInt()
    val mode = resolveQuickNotesLayoutMode(widthDp = widthDp, heightDp = heightDp)

    if (mode == QuickNotesLayoutMode.COMPACT_BAR) {
        CompactCaptureBarContent(context = context, snapshot = snapshot)
        return
    }

    val todayCount = snapshot.stats?.todayCount ?: 0
    val badgeText =
        if (snapshot.availability == WidgetSnapshotAvailability.READY && todayCount > 0) {
            context.resources.getQuantityString(R.plurals.widget_today_count_badge, todayCount, todayCount)
        } else {
            null
        }

    WidgetShell {
        WidgetHeaderRow(
            context = context,
            iconRes = R.drawable.ic_note_add,
            titleRes = R.string.widget_quick_notes_label,
            titleClick = widgetLaunchAction(context, WidgetLaunchTarget.DefaultMain),
            badgeText = badgeText,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WidgetActionIconButton(
                    iconRes = R.drawable.ic_widget_search,
                    contentDescription = context.getString(R.string.widget_action_search),
                    onClick =
                    widgetLaunchAction(
                        context,
                        WidgetLaunchTarget.OpenDestination(WidgetDestination.SEARCH),
                    ),
                    sizeDp = 30.dp,
                    iconSizeDp = 15.dp,
                )
                Spacer(GlanceModifier.width(6.dp))
                WidgetActionIconButton(
                    iconRes = R.drawable.ic_mic,
                    contentDescription = context.getString(R.string.widget_action_record),
                    onClick = widgetLaunchAction(context, WidgetLaunchTarget.StartRecording),
                    sizeDp = 30.dp,
                    iconSizeDp = 15.dp,
                )
                Spacer(GlanceModifier.width(6.dp))
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
        }
        Spacer(GlanceModifier.height(10.dp))
        QuickNotesFeedBody(
            context = context,
            snapshot = snapshot,
            nowMillis = nowMillis,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            isPreview = isPreview,
        )
    }
}

@Composable
private fun CompactCaptureBarContent(context: Context, snapshot: WidgetRenderSnapshot) {
    WidgetShell(contentPadding = 10.dp) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                GlanceModifier
                    .defaultWeight()
                    .height(42.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(21.dp)
                    .clickable(widgetLaunchAction(context, WidgetLaunchTarget.CreateMemo))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_plus),
                    contentDescription = null,
                    modifier = GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text =
                    if (snapshot.availability == WidgetSnapshotAvailability.REDACTED) {
                        context.getString(R.string.widget_redacted_summary)
                    } else {
                        context.getString(R.string.widget_action_new_memo)
                    },
                    maxLines = 1,
                    style =
                    TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            WidgetActionIconButton(
                iconRes = R.drawable.ic_mic,
                contentDescription = context.getString(R.string.widget_action_record),
                onClick = widgetLaunchAction(context, WidgetLaunchTarget.StartRecording),
                sizeDp = 42.dp,
                iconSizeDp = 18.dp,
            )
            Spacer(GlanceModifier.width(8.dp))
            WidgetActionIconButton(
                iconRes = R.drawable.ic_widget_search,
                contentDescription = context.getString(R.string.widget_action_search),
                onClick =
                widgetLaunchAction(
                    context,
                    WidgetLaunchTarget.OpenDestination(WidgetDestination.SEARCH),
                ),
                sizeDp = 42.dp,
                iconSizeDp = 18.dp,
            )
        }
    }
}

@Composable
private fun QuickNotesFeedBody(
    context: Context,
    snapshot: WidgetRenderSnapshot,
    nowMillis: Long,
    modifier: GlanceModifier = GlanceModifier,
    isPreview: Boolean = false,
) {
    Box(modifier = modifier) {
        when (snapshot.availability) {
            WidgetSnapshotAvailability.UNAVAILABLE ->
                WidgetNoticeCard(
                    context = context,
                    iconRes = R.drawable.ic_note_add,
                    message = context.getString(R.string.widget_snapshot_unavailable),
                )

            WidgetSnapshotAvailability.REDACTED ->
                WidgetNoticeCard(
                    context = context,
                    iconRes = R.drawable.ic_widget_lock,
                    message =
                    if (snapshot.items.isNotEmpty()) {
                        context.getString(R.string.widget_redacted_preview)
                    } else {
                        context.getString(R.string.widget_redacted_summary)
                    },
                )

            WidgetSnapshotAvailability.READY -> {
                if (snapshot.items.isEmpty()) {
                    WidgetNoticeCard(
                        context = context,
                        iconRes = R.drawable.ic_note_add,
                        message = context.getString(R.string.widget_no_memos),
                        onClick = widgetLaunchAction(context, WidgetLaunchTarget.CreateMemo),
                    )
                } else if (isPreview) {
                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        snapshot.items.forEachIndexed { index, item ->
                            QuickNotesMemoCard(
                                context = context,
                                item = item,
                                nowMillis = nowMillis,
                            )
                            if (index < snapshot.items.lastIndex) {
                                Spacer(GlanceModifier.height(6.dp))
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        itemsIndexed(
                            items = snapshot.items,
                            itemId = { _, item -> stableWidgetListItemId(item.id) },
                        ) { index, item ->
                            Column(modifier = GlanceModifier.fillMaxWidth()) {
                                QuickNotesMemoCard(
                                    context = context,
                                    item = item,
                                    nowMillis = nowMillis,
                                )
                                if (index < snapshot.items.lastIndex) {
                                    Spacer(GlanceModifier.height(6.dp))
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
private fun QuickNotesMemoCard(context: Context, item: WidgetGlanceSnapshotItem, nowMillis: Long) {
    Column(
        modifier =
        GlanceModifier
            .fillMaxWidth()
            .background(WidgetLayeredCardSurface)
            .cornerRadius(WidgetCardCornerRadius)
            .clickable(widgetLaunchAction(context, WidgetLaunchTarget.OpenMemo(item.id)))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.isPinned) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_pin),
                    contentDescription = context.getString(R.string.widget_pinned_badge),
                    modifier = GlanceModifier.size(12.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                )
                Spacer(GlanceModifier.width(4.dp))
            }
            Text(
                text = formatWidgetRelativeTime(timestampMillis = item.timestampMillis, nowMillis = nowMillis),
                maxLines = 1,
                style =
                TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
            Spacer(GlanceModifier.defaultWeight())
            item.tags.take(QUICK_NOTES_CARD_MAX_TAGS).forEach { tag ->
                Spacer(GlanceModifier.width(4.dp))
                WidgetPillLabel(text = "#$tag")
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = item.previewText,
            maxLines = 2,
            style =
            TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
            ),
        )
    }
}

internal data class WidgetMemoItemPresentation(
    val id: String,
    val timestampMillis: Long,
    val nowMillis: Long,
    val previewText: String,
)

internal fun resolveWidgetMemoItemPresentation(memo: Memo, nowMillis: Long): WidgetMemoItemPresentation {
    // memo.content for widget queries is already the engine-owned plain-text preview; the host
    // only bounds and collapses it, never re-renders markdown in a projection process.
    val processedContent = MarkdownCleanupFormatter.collapseSpacing(memo.content)
    val previewText =
        processedContent.take(WIDGET_MEMO_PREVIEW_LENGTH).let { preview ->
            if (processedContent.length > WIDGET_MEMO_PREVIEW_LENGTH) "$preview..." else preview
        }
    return WidgetMemoItemPresentation(
        id = memo.id,
        timestampMillis = memo.timestamp,
        nowMillis = nowMillis,
        previewText = previewText,
    )
}

private const val WIDGET_MEMO_PREVIEW_LENGTH = 100
private const val WIDGET_COMPACT_HEIGHT_DP = 140
private const val WIDGET_COMPACT_WIDTH_DP = 160
private const val WIDGET_EXPANDED_HEIGHT_DP = 210
private const val QUICK_NOTES_CARD_MAX_TAGS = 2
private const val PREVIEW_WIDTH_DP = 250
private const val PREVIEW_HEIGHT_DP = 160
