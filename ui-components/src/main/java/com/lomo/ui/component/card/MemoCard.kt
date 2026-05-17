package com.lomo.ui.component.card

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.ui.R
import com.lomo.ui.component.markdown.MarkdownKnownTagFilter
import com.lomo.ui.text.MemoParagraphText
import com.lomo.ui.text.MemoTextSelectionRegistrar
import com.lomo.ui.text.normalizeCjkMixedSpacingForDisplay
import com.lomo.ui.text.scriptAwareFor
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.memoSummaryTextStyle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MemoCard(
    content: String,
    processedContent: String,
    timestamp: Long,
    tags: ImmutableList<String>,
    modifier: Modifier = Modifier,
    menuButtonModifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    precomputedRenderPlan: com.lomo.ui.component.markdown.ModernMarkdownRenderPlan? = null,
    dateFormat: String = "yyyy-MM-dd",
    timeFormat: String = "HH:mm",
    isPinned: Boolean = false,
    allowFreeTextCopy: Boolean = false,
    expandOnClick: Boolean = false,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    onTagClick: (String) -> Unit = {},
    onTodoClick: ((Int, Boolean) -> Unit)? = null,
    todoOverrides: ImmutableMap<Int, Boolean> = persistentHashMapOf(), // State overlay for checkboxes
    onImageClick: ((String) -> Unit)? = null,
    shouldShowExpand: Boolean = shouldShowMemoCardExpand(content),
    collapsedSummary: String = buildMemoCardCollapsedSummary(content, tags),
    menuContent: (@Composable () -> Unit)? = null,
) {
    var internalExpanded by rememberSaveable(timestamp) { mutableStateOf(false) }
    val effectiveExpanded = isExpanded ?: internalExpanded
    val updateExpanded: (Boolean) -> Unit = { expanded ->
        if (isExpanded == null) {
            internalExpanded = expanded
        }
        onExpandedChange?.invoke(expanded)
    }
    val isCollapsedPreview = shouldShowExpand && !effectiveExpanded
    val collapsedPreviewMode =
        resolveMemoCardCollapsedPreviewMode(
            isCollapsedPreview = isCollapsedPreview,
            hasProcessedContent = processedContent.isNotBlank(),
            collapsedSummary = collapsedSummary,
        )
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val dateTimeFormatter = remember(dateFormat, timeFormat) { DateTimeFormatter.ofPattern("$dateFormat $timeFormat") }
    val cardInteractionSource = remember { MutableInteractionSource() }
    val cardFeedbackScope = rememberCoroutineScope()
    val memoCardTapFeedback = {
        cardFeedbackScope.launch {
            emitMemoCardPressFeedback(cardInteractionSource)
        }
        Unit
    }
    val quickEditOnDoubleClick =
        onDoubleClick?.let { doubleClick ->
            {
                haptic.medium()
                doubleClick()
            }
        }
    // Plain memo-body taps intentionally mirror the footer toggle: collapsed taps expand and expanded taps collapse.
    val effectiveOnClick: () -> Unit = if (expandOnClick && shouldShowExpand) {
        {
            updateExpanded(!effectiveExpanded)
            onClick()
        }
    } else {
        onClick
    }
    val interactionModifier =
        Modifier.rememberMemoCardInteractionModifier(
            allowFreeTextCopy = allowFreeTextCopy,
            cardInteractionSource = cardInteractionSource,
            haptic = haptic,
            onClick = effectiveOnClick,
            onDoubleClick = quickEditOnDoubleClick,
            onMenuClick = onMenuClick,
        )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.Medium,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(
            modifier = interactionModifier.padding(16.dp),
        ) {
            MemoCardHeader(
                timestamp = timestamp,
                dateTimeFormatter = dateTimeFormatter,
                isPinned = isPinned,
                onMenuClick = onMenuClick,
                haptic = haptic,
                modifier = menuButtonModifier,
                menuContent = menuContent,
            )
            Spacer(modifier = Modifier.height(AppSpacing.Small))
            MemoCardBody(
                processedContent = processedContent,
                tags = tags,
                precomputedRenderPlan = precomputedRenderPlan,
                shouldShowExpand = shouldShowExpand,
                isCollapsedPreview = isCollapsedPreview,
                collapsedPreviewMode = collapsedPreviewMode,
                collapsedSummary = collapsedSummary,
                isExpanded = effectiveExpanded,
                allowFreeTextCopy = allowFreeTextCopy,
                onTapFeedback = memoCardTapFeedback,
                onBodyClick = effectiveOnClick,
                onDoubleClick = quickEditOnDoubleClick,
                onTodoClick = onTodoClick,
                todoOverrides = todoOverrides,
                onImageClick = onImageClick,
            )
            MemoCardFooter(
                tags = tags,
                shouldShowExpand = shouldShowExpand,
                isExpanded = effectiveExpanded,
                haptic = haptic,
                onTagClick = onTagClick,
                onToggleExpanded = { updateExpanded(!effectiveExpanded) },
            )
        }
    }
}

@Composable
private fun Modifier.rememberMemoCardInteractionModifier(
    allowFreeTextCopy: Boolean,
    cardInteractionSource: MutableInteractionSource,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)?,
    onMenuClick: (() -> Unit)?,
): Modifier =
    if (allowFreeTextCopy) {
        clip(AppShapes.Medium)
    } else {
        this
            .clip(AppShapes.Medium)
            .combinedClickable(
                interactionSource = cardInteractionSource,
                indication = LocalIndication.current,
                onClick = {
                    haptic.medium()
                    onClick()
                },
                onDoubleClick = onDoubleClick,
                onLongClick = onMenuClick?.let { menuClick -> { haptic.longPress(); menuClick() } },
            )
    }

private suspend fun emitMemoCardPressFeedback(
    interactionSource: MutableInteractionSource,
) {
    val press = PressInteraction.Press(Offset.Zero)
    interactionSource.emit(press)
    delay(MEMO_CARD_PRESS_FEEDBACK_MILLIS)
    interactionSource.emit(PressInteraction.Release(press))
}

@Composable
private fun MemoCardHeader(
    timestamp: Long,
    dateTimeFormatter: DateTimeFormatter,
    isPinned: Boolean,
    onMenuClick: (() -> Unit)?,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    modifier: Modifier = Modifier,
    menuContent: (@Composable () -> Unit)?,
) {
    val timeStr =
        remember(timestamp, dateTimeFormatter) {
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MemoCardHeaderActions(
            isPinned = isPinned,
            onMenuClick = onMenuClick,
            haptic = haptic,
            modifier = modifier,
            menuContent = menuContent,
        )
    }
}

@Composable
private fun MemoCardHeaderActions(
    isPinned: Boolean,
    onMenuClick: (() -> Unit)?,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    modifier: Modifier = Modifier,
    menuContent: (@Composable () -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPinned) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = AppShapes.Small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(R.string.memo_pinned_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        Box {
            if (onMenuClick != null) {
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    onClick = {
                        haptic.medium()
                        onMenuClick()
                    },
                    modifier = Modifier.size(20.dp).then(modifier),
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
}

@Composable
private fun MemoCardBody(
    processedContent: String,
    tags: ImmutableList<String>,
    precomputedRenderPlan: com.lomo.ui.component.markdown.ModernMarkdownRenderPlan?,
    shouldShowExpand: Boolean,
    isCollapsedPreview: Boolean,
    collapsedPreviewMode: MemoCardCollapsedPreviewMode,
    collapsedSummary: String,
    isExpanded: Boolean,
    allowFreeTextCopy: Boolean,
    onTapFeedback: (() -> Unit)?,
    onBodyClick: (() -> Unit)?,
    onDoubleClick: (() -> Unit)?,
    onTodoClick: ((Int, Boolean) -> Unit)?,
    todoOverrides: ImmutableMap<Int, Boolean>,
    onImageClick: ((String) -> Unit)?,
) {
    val bodyTransitionMode = resolveMemoCardBodyTransitionMode(shouldShowExpand = shouldShowExpand)
    val containerSizeAnimation =
        resolveMemoCardBodyContainerSizeAnimation(bodyTransitionMode)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .let { base ->
                    when (containerSizeAnimation) {
                        MemoCardBodyContainerSizeAnimation.Enabled ->
                            base.animateContentSize(
                                animationSpec =
                                    tween(
                                        durationMillis = MEMO_CARD_EXPAND_ANIMATION_DURATION_MS,
                                    ),
                            )

                        MemoCardBodyContainerSizeAnimation.Disabled -> base
                    }
                },
    ) {
        MemoCardBodyContent(
            collapsedPreviewMode = collapsedPreviewMode,
            collapsedSummary = collapsedSummary,
            allowFreeTextCopy = allowFreeTextCopy,
            onTapFeedback = onTapFeedback,
            onBodyClick = onBodyClick,
            onDoubleClick = onDoubleClick,
            processedContent = processedContent,
            precomputedRenderPlan = precomputedRenderPlan,
            tags = tags,
            isExpanded = isExpanded,
            isCollapsedPreview = isCollapsedPreview,
            onTodoClick = onTodoClick,
            todoOverrides = todoOverrides,
            onImageClick = onImageClick,
            bodyTransitionMode = bodyTransitionMode,
        )
    }
}

@Composable
internal fun MemoCardCollapsedSummary(
    collapsedSummary: String,
    allowFreeTextCopy: Boolean,
    onTapFeedback: (() -> Unit)?,
    onBodyClick: (() -> Unit)?,
    onDoubleClick: (() -> Unit)?,
    selectionRegistrar: MemoTextSelectionRegistrar? = null,
) {
    val displaySummary = collapsedSummary.normalizeCjkMixedSpacingForDisplay()
    val summaryStyle =
        MaterialTheme.typography.memoSummaryTextStyle()
            .copy(color = MaterialTheme.colorScheme.onSurface)
            .scriptAwareFor(displaySummary)
    MemoParagraphText(
        text = displaySummary,
        style = summaryStyle,
        maxLines = COLLAPSED_SUMMARY_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        selectable = allowFreeTextCopy,
        selectionRegistrar = selectionRegistrar,
        onTapFeedback = onTapFeedback,
        onBodyClick = onBodyClick,
        onDoubleClick = onDoubleClick,
    )
}

@Composable
private fun MemoCardFooter(
    tags: ImmutableList<String>,
    shouldShowExpand: Boolean,
    isExpanded: Boolean,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    onTagClick: (String) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
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
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.height(24.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        if (shouldShowExpand) {
            val label = if (isExpanded) R.string.cd_collapse else R.string.cd_expand
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
                TextButton(
                    onClick = {
                        haptic.medium()
                        onToggleExpanded()
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.heightIn(min = 24.dp),
                ) {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private const val EXPAND_CHAR_THRESHOLD = 600
private const val EXPAND_LINE_THRESHOLD = 15
private const val MEMO_CARD_EXPAND_ANIMATION_DURATION_MS = 250
private const val MEMO_CARD_PRESS_FEEDBACK_MILLIS = 120L
internal const val COLLAPSED_MAX_VISIBLE_BLOCKS = 6
private const val COLLAPSED_SUMMARY_MAX_LINES = 8
private const val COLLAPSED_SUMMARY_MAX_CHARS = 420

fun shouldShowMemoCardExpand(content: String): Boolean =
    content.length > EXPAND_CHAR_THRESHOLD ||
        content.lineSequence().count() > EXPAND_LINE_THRESHOLD

fun buildMemoCardCollapsedSummary(
    content: String,
    tags: Iterable<String> = emptyList(),
): String {
    if (content.isBlank()) return ""

    val lines = mutableListOf<String>()
    var charCount = 0
    val lineIterator = content.lineSequence().iterator()

    while (
        lineIterator.hasNext() &&
        lines.size < COLLAPSED_SUMMARY_MAX_LINES &&
        charCount < COLLAPSED_SUMMARY_MAX_CHARS
    ) {
        val line = sanitizeCollapsedSummaryLine(lineIterator.next(), tags)
        val remaining = COLLAPSED_SUMMARY_MAX_CHARS - charCount
        val clipped = if (line.length > remaining) line.take(remaining).trimEnd() else line

        if (clipped.isNotBlank()) {
            lines.add(clipped)
            charCount += clipped.length
        }
    }

    return lines.joinToString(separator = "\n")
}

private fun sanitizeCollapsedSummaryLine(
    rawLine: String,
    tags: Iterable<String>,
): String =
    MarkdownKnownTagFilter
        .stripInlineTags(
            input =
                rawLine
                    .replace(MARKDOWN_IMAGE_PATTERN, "")
                    .replace(MARKDOWN_LINK_PATTERN, "$1")
                    .replace(MARKDOWN_INLINE_CODE_PATTERN, "$1")
                    .replace(MARKDOWN_BLOCK_PREFIX_PATTERN, "")
                    .replace(MARKDOWN_TASK_PREFIX_PATTERN, ""),
            tags = tags,
        ).trim()

private val MARKDOWN_IMAGE_PATTERN = Regex("""!\[[^\]]*]\([^)]+\)""")
private val MARKDOWN_LINK_PATTERN = Regex("""\[([^\]]+)]\([^)]+\)""")
private val MARKDOWN_INLINE_CODE_PATTERN = Regex("""`([^`]+)`""")
private val MARKDOWN_BLOCK_PREFIX_PATTERN = Regex("""^\s{0,3}(?:#{1,6}\s+|>\s+|[-*+]\s+|\d+\.\s+)""")
private val MARKDOWN_TASK_PREFIX_PATTERN = Regex("""^\s*\[[ xX]\]\s+""")
