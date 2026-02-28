package com.lomo.ui.component.card

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.ui.R
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.text.scriptAwareTextAlign
import kotlinx.collections.immutable.ImmutableList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MemoCard(
    content: String,
    processedContent: String,
    precomputedNode: com.lomo.ui.component.markdown.ImmutableNode? = null,
    timestamp: Long,
    tags: ImmutableList<String>,
    modifier: Modifier = Modifier,
    dateFormat: String = "yyyy-MM-dd",
    timeFormat: String = "HH:mm",
    onClick: () -> Unit = {},
    onDoubleClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    onTagClick: (String) -> Unit = {},
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: Map<Int, Boolean> = emptyMap(), // State overlay for checkboxes
    onImageClick: ((String) -> Unit)? = null,
    menuContent: (@Composable () -> Unit)? = null,
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Heuristic for long content: > 600 chars or > 15 lines
    val shouldShowExpand =
        remember(content) {
            content.length > EXPAND_CHAR_THRESHOLD ||
                content.lines().size > EXPAND_LINE_THRESHOLD
        }
    val isCollapsedPreview = shouldShowExpand && !isExpanded
    val collapsedSummary =
        remember(content) {
            buildCollapsedSummary(content)
        }
    val useCollapsedSummary = isCollapsedPreview && precomputedNode == null && collapsedSummary.isNotBlank()

    // Note: toggleTodo logic is now handled by MarkdownRenderer via onTodoClick callback

    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val dateTimeFormatter =
        remember(dateFormat, timeFormat) {
            DateTimeFormatter.ofPattern("$dateFormat $timeFormat")
        }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.Medium,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .clip(AppShapes.Medium)
                    .combinedClickable(
                        onClick = {
                            haptic.medium()
                            onClick()
                        },
                        onDoubleClick =
                            onDoubleClick?.let { doubleClick ->
                                {
                                    haptic.medium()
                                    doubleClick()
                                }
                            },
                        onLongClick =
                            onMenuClick?.let { menuClick ->
                                {
                                    haptic.longPress()
                                    menuClick()
                                }
                            },
                    ).padding(16.dp),
        ) {
            // Increased padding
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val timeStr =
                    Instant
                        .ofEpochMilli(timestamp)
                        .atZone(ZoneId.systemDefault())
                        .format(dateTimeFormatter)
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelMedium, // Slightly larger/clearer
                    color = MaterialTheme.colorScheme.onSurfaceVariant, // Softer than outline, readable
                )

                Box {
                    if (onMenuClick != null) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            onClick = {
                                haptic.medium()
                                onMenuClick()
                            },
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.cd_more_options),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    menuContent?.invoke()
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Small))

            // Markdown Content
            Box(
                modifier =
                    Modifier
                        .then(if (shouldShowExpand) Modifier.animateContentSize() else Modifier)
                        .let {
                            if (shouldShowExpand && !isExpanded) {
                                it.heightIn(max = 240.dp)
                            } else {
                                it
                            }
                        }.clip(AppShapes.Small),
            ) {
                ProvideTextStyle(value = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)) {
                    if (useCollapsedSummary) {
                        val displaySummary = collapsedSummary.normalizeCjkMixedSpacingForDisplay()
                        val summaryStyle =
                            MaterialTheme.typography
                                .bodyLarge
                                .copy(lineHeight = 24.sp)
                                .scriptAwareFor(displaySummary)
                        Text(
                            text = displaySummary,
                            style = summaryStyle,
                            textAlign = displaySummary.scriptAwareTextAlign(),
                            maxLines = COLLAPSED_SUMMARY_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    } else {
                        MarkdownRenderer(
                            content = processedContent,
                            precomputedNode = precomputedNode,
                            modifier = Modifier.padding(vertical = 4.dp),
                            maxVisibleBlocks =
                                if (isCollapsedPreview) {
                                    COLLAPSED_MAX_VISIBLE_BLOCKS
                                } else {
                                    Int.MAX_VALUE
                                },
                            onTodoClick = onTodoClick,
                            todoOverrides = todoOverrides,
                            onImageClick = onImageClick,
                        )
                    }
                }

                if (isCollapsedPreview) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(
                                    brush =
                                        Brush.verticalGradient(
                                            colors =
                                                listOf(
                                                    Color.Transparent,
                                                    MaterialTheme.colorScheme.surfaceContainer,
                                                ),
                                        ),
                                ),
                    )
                }
            }

            // Tags & Expand Button
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { tag ->
                        Surface(
                            onClick = {
                                haptic.medium()
                                onTagClick(tag)
                            },
                            shape = AppShapes.Small,
                            color =
                                MaterialTheme.colorScheme
                                    .secondaryContainer,
                            modifier = Modifier.height(24.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier.padding(
                                        horizontal = 8.dp,
                                    ),
                            ) {
                                Text(
                                    text = "#$tag",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSecondaryContainer,
                                )
                            }
                        }
                    }
                }

                if (shouldShowExpand) {
                    if (!isExpanded) {
                        TextButton(
                            onClick = {
                                haptic.medium()
                                isExpanded = true
                            },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(R.string.cd_expand),
                                style =
                                    MaterialTheme.typography
                                        .labelSmall,
                            )
                        }
                    } else {
                        TextButton(
                            onClick = {
                                haptic.medium()
                                isExpanded = false
                            },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(R.string.cd_collapse),
                                style =
                                    MaterialTheme.typography
                                        .labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val EXPAND_CHAR_THRESHOLD = 600
private const val EXPAND_LINE_THRESHOLD = 15
private const val COLLAPSED_MAX_VISIBLE_BLOCKS = 6
private const val COLLAPSED_SUMMARY_MAX_LINES = 8
private const val COLLAPSED_SUMMARY_MAX_CHARS = 420

private fun buildCollapsedSummary(content: String): String {
    if (content.isBlank()) return ""

    val lines = mutableListOf<String>()
    var charCount = 0

    for (rawLine in content.lineSequence()) {
        if (lines.size >= COLLAPSED_SUMMARY_MAX_LINES || charCount >= COLLAPSED_SUMMARY_MAX_CHARS) {
            break
        }

        val line =
            rawLine
                .replace(MARKDOWN_IMAGE_PATTERN, "")
                .replace(MARKDOWN_LINK_PATTERN, "$1")
                .replace(MARKDOWN_INLINE_CODE_PATTERN, "$1")
                .replace(MARKDOWN_BLOCK_PREFIX_PATTERN, "")
                .replace(MARKDOWN_TASK_PREFIX_PATTERN, "")
                .trim()
        if (line.isBlank()) continue

        val remaining = COLLAPSED_SUMMARY_MAX_CHARS - charCount
        if (remaining <= 0) break

        val clipped = if (line.length > remaining) line.take(remaining).trimEnd() else line
        if (clipped.isBlank()) break

        lines.add(clipped)
        charCount += clipped.length
    }

    return lines.joinToString(separator = "\n")
}

private val MARKDOWN_IMAGE_PATTERN = Regex("""!\[[^\]]*]\([^)]+\)""")
private val MARKDOWN_LINK_PATTERN = Regex("""\[([^\]]+)]\([^)]+\)""")
private val MARKDOWN_INLINE_CODE_PATTERN = Regex("""`([^`]+)`""")
private val MARKDOWN_BLOCK_PREFIX_PATTERN = Regex("""^\s{0,3}(?:#{1,6}\s+|>\s+|[-*+]\s+|\d+\.\s+)""")
private val MARKDOWN_TASK_PREFIX_PATTERN = Regex("""^\s*\[[ xX]\]\s+""")
