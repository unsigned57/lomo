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
import com.lomo.domain.model.Memo
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Lomo App Widget using Jetpack Glance.
 * Displays the most recent memos and allows quick access to create new ones.
 * Design inspired by MoeMemos with Material You theming.
 */
class LomoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        // Access DAO via Hilt EntryPoint
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java,
            )
        val memoRepository = entryPoint.memoRepository()

        // Fetch recent memos
        val recentMemos =
            withContext(Dispatchers.IO) {
                try {
                    memoRepository.getRecentMemos(3)
                } catch (e: Exception) {
                    emptyList()
                }
            }

        provideContent {
            GlanceTheme {
                WidgetContent(context, recentMemos)
            }
        }
    }

    @Composable
    private fun WidgetContent(
        context: Context,
        memos: List<Memo>,
    ) {
        // Main container with rounded corners and white background
        Box(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(24.dp)
                    .background(GlanceTheme.colors.surface)
                    .padding(16.dp),
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                // Header Row
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // App Icon
                    Image(
                        provider = ImageProvider(R.drawable.ic_launcher),
                        contentDescription = null,
                        modifier = GlanceModifier.size(24.dp),
                    )
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    // App Name
                    Text(
                        text = "Lomo",
                        style =
                            TextStyle(
                                color = GlanceTheme.colors.primary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                    Spacer(modifier = GlanceModifier.defaultWeight())
                    // Add Button
                    Box(
                        modifier =
                            GlanceModifier
                                .size(32.dp)
                                .cornerRadius(16.dp)
                                .background(GlanceTheme.colors.primaryContainer)
                                .clickable(
                                    actionStartActivity(
                                        Intent(context, MainActivity::class.java).apply {
                                            action = MainActivity.ACTION_NEW_MEMO
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        },
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

                Spacer(modifier = GlanceModifier.height(12.dp))

                // Content Area
                if (memos.isEmpty()) {
                    // Empty State
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
                } else {
                    // Memo List
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        memos.forEachIndexed { index, memo ->
                            MemoItem(context, memo, isLast = index == memos.size - 1)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MemoItem(
        context: Context,
        memo: Memo,
        isLast: Boolean = false,
    ) {
        val processedContent = stripMarkdown(memo.content)

        // Card-like container
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
            // Relative time
            Text(
                text =
                    DateUtils
                        .getRelativeTimeSpanString(
                            memo.timestamp,
                            Instant.now().toEpochMilli(),
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString(),
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                    ),
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            // Content preview
            Text(
                text =
                    processedContent.take(100).let {
                        if (processedContent.length > 100) "$it..." else it
                    },
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                    ),
                maxLines = 2,
            )
        }

        // Spacing between items (except last)
        if (!isLast) {
            Spacer(modifier = GlanceModifier.height(8.dp))
        }
    }

    private fun stripMarkdown(content: String): String {
        var str = content
        str = str.replace(Regex("(?m)^#{1,6}\\s+"), "")
        str = str.replace(Regex("(\\*\\*|__)"), "")
        str = str.replace(Regex("(?m)^\\s*[-*+]\\s*\\[ \\]"), "☐")
        str = str.replace(Regex("(?m)^\\s*[-*+]\\s*\\[x\\]"), "☑")
        str = str.replace(Regex("!\\[.*?\\]\\(.*?\\)"), "[Image]")
        str = str.replace(Regex("!\\[\\[(.*?)\\]\\]"), "[Image: $1]")
        str = str.replace(Regex("(?<!!)\\[(.*?)\\]\\(.*?\\)"), "$1")
        str = str.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        return str.trim()
    }
}
