package com.lomo.ui.component.card

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lomo.ui.component.markdown.MarkdownRenderer
import com.lomo.ui.theme.AppShapes
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

    // Note: toggleTodo logic is now handled by MarkdownRenderer via onTodoClick callback

    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

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
                verticalAlignment = Alignment.Top,
            ) {
                val timeStr =
                    Instant
                        .ofEpochMilli(timestamp)
                        .atZone(ZoneId.systemDefault())
                        .format(
                            DateTimeFormatter.ofPattern(
                                "$dateFormat $timeFormat",
                            ),
                        )
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelMedium, // Slightly larger/clearer
                    color = MaterialTheme.colorScheme.onSurfaceVariant, // Softer than outline, readable
                )

                Box {
                    if (onMenuClick != null) {
                        IconButton(
                            onClick = {
                                haptic.medium()
                                onMenuClick()
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                androidx.compose.material.icons.Icons.Rounded.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    menuContent?.invoke()
                }
            }

            Spacer(modifier = Modifier.height(12.dp)) // More breathing room between header and content

            // Markdown Content
            Box(
                modifier =
                    Modifier
                        .animateContentSize()
                        .let {
                            if (shouldShowExpand && !isExpanded) {
                                it.heightIn(max = 240.dp)
                            } else {
                                it
                            }
                        }.clip(AppShapes.Small),
            ) {
                ProvideTextStyle(value = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)) {
                    // Improve readability
                    MarkdownRenderer(
                        content = processedContent,
                        precomputedNode = precomputedNode,
                        modifier = Modifier.padding(vertical = 4.dp),
                        onTodoClick = onTodoClick,
                        todoOverrides = todoOverrides,
                        onImageClick = onImageClick,
                    )
                }

                if (shouldShowExpand && !isExpanded) {
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
                            modifier = Modifier.height(24.dp),
                        ) {
                            Text(
                                "Expand", // Or "Show More"
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
                            modifier = Modifier.height(24.dp),
                        ) {
                            Text(
                                "Collapse", // Or "Show Less"
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
