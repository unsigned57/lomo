package com.lomo.app.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.lomo.app.MainActivity
import com.lomo.app.R
import com.lomo.app.TrustedLaunchIntents
import com.lomo.app.util.MarkdownCleanupFormatter
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoListQueryRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Lomo App Widget using Jetpack Glance.
 * Displays the most recent memos and allows quick access to create new ones.
 * Design inspired by MoeMemos with Material You theming.
 */
class LomoWidget : GlanceAppWidget(), KoinComponent {
    private val memoListQueryRepository: MemoListQueryRepository by lazy { get() }

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        // Fetch recent memos
        val recentMemos =
            withContext(Dispatchers.IO) {
                runCatching {
                    memoListQueryRepository.getRecentMemos(WIDGET_MEMO_LIMIT).toImmutableList()
                }.getOrElse { persistentListOf() }
            }

        val nowMillis = Instant.now().toEpochMilli()
        provideContent {
            GlanceTheme {
                WidgetContent(context, recentMemos, nowMillis)
            }
        }
    }
}

@Composable
private fun WidgetContent(
    context: Context,
    memos: ImmutableList<Memo>,
    nowMillis: Long,
) {
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(GlanceTheme.colors.surface)
                .padding(16.dp),
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            WidgetHeader(context)
            Spacer(modifier = GlanceModifier.height(12.dp))
            if (memos.isEmpty()) {
                WidgetEmptyState(context)
            } else {
                WidgetMemoList(context, memos, nowMillis)
            }
        }
    }
}

@Composable
private fun WidgetHeader(context: Context) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_launcher_legacy),
            contentDescription = context.getString(R.string.app_name),
            modifier = GlanceModifier.size(48.dp),
        )
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = context.getString(R.string.app_name),
            style =
                TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        Box(
            modifier =
                GlanceModifier
                    .size(32.dp)
                    .cornerRadius(16.dp)
                    .background(GlanceTheme.colors.primaryContainer)
                    .clickable(
                        actionStartActivity(
                            TrustedLaunchIntents.create(context).trustedWidgetCreateMemoIntent(),
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
        }
    }
}

@Composable
private fun WidgetEmptyState(context: Context) {
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = context.getString(R.string.widget_no_memos),
            style =
                TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 14.sp,
                ),
        )
    }
}

@Composable
private fun WidgetMemoList(
    context: Context,
    memos: ImmutableList<Memo>,
    nowMillis: Long,
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        memos.forEachIndexed { index, memo ->
            MemoItem(context, memo, nowMillis, isLast = index == memos.size - 1)
        }
    }
}

@Composable
private fun MemoItem(
    context: Context,
    memo: Memo,
    nowMillis: Long,
    isLast: Boolean = false,
) {
    val presentation = resolveWidgetMemoItemPresentation(memo = memo, nowMillis = nowMillis)

    Column(
        modifier =
            GlanceModifier
                .fillMaxWidth()
                .cornerRadius(12.dp)
                .background(GlanceTheme.colors.surfaceVariant)
                .clickable(
                    actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_OPEN_MEMO
                            putExtra(MainActivity.EXTRA_MEMO_ID, memo.id)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                    ),
                ).padding(12.dp),
    ) {
        Text(
            text =
                DateUtils
                    .getRelativeTimeSpanString(
                        presentation.timestampMillis,
                        presentation.nowMillis,
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
            style =
                TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text =
                presentation.previewText,
            style =
                TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                ),
            maxLines = 2,
        )
    }

    if (!isLast) {
        Spacer(modifier = GlanceModifier.height(8.dp))
    }
}

internal data class WidgetMemoItemPresentation(
    val id: String,
    val timestampMillis: Long,
    val nowMillis: Long,
    val previewText: String,
)

internal fun resolveWidgetMemoItemPresentation(
    memo: Memo,
    nowMillis: Long,
): WidgetMemoItemPresentation {
    val processedContent = stripWidgetMarkdown(memo.content)
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

private fun stripWidgetMarkdown(content: String): String = MarkdownCleanupFormatter.stripForPlainText(content)

private const val WIDGET_MEMO_LIMIT = 3
private const val WIDGET_MEMO_PREVIEW_LENGTH = 100
