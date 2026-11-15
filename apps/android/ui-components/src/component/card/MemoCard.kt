package com.lomo.ui.component.card

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.ReminderMarker
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.common.lomoCompactInteractiveHeight
import com.lomo.ui.component.markdown.MarkdownIrPresentationPlan
import com.lomo.ui.component.markdown.MarkdownMediaPresentation
import com.lomo.ui.component.markdown.MarkdownMediaPresentationResolver
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.cd_collapse
import com.lomo.ui.generated.resources.cd_expand
import com.lomo.ui.generated.resources.cd_more_options
import com.lomo.ui.generated.resources.memo_card_pending
import com.lomo.ui.generated.resources.memo_pinned_badge
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
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
    renderDocument: MarkdownRenderDocument,
    dateFormat: String,
    timeFormat: String,
    collapsedSummary: String,
    modifier: Modifier = Modifier,
    menuButtonModifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    presentationPlan: MarkdownIrPresentationPlan? = null,
    isPinned: Boolean = false,
    /** The engine published this memo as a pending create; its durable commit is still in flight. */
    isPending: Boolean = false,
    allowFreeTextCopy: Boolean = false,
    expandOnClick: Boolean = false,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    onTagClick: (String) -> Unit = {},
    onTodoClick: ((MarkdownSourceSpan) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    shouldShowExpand: Boolean = false,
    reminders: ImmutableList<ReminderMarker> = persistentListOf(),
    onReminderClick: (ReminderMarker) -> Unit = {},
    menuContent: (@Composable () -> Unit)? = null,
    mediaPresentationResolver: MarkdownMediaPresentationResolver? = null,
    mediaContent: (@Composable (MarkdownMediaPresentation) -> Unit)? = null,
) {
    var bodyHeightPx by remember(content, renderDocument, presentationPlan) { mutableIntStateOf(0) }
    val collapsedHeightPx = with(LocalDensity.current) { MemoCardTokens.CollapsedBodyMaxHeight.roundToPx() }
    val hasImages = renderDocument.attachments.any { it.mediaClass == com.lomo.domain.model.MediaClass.IMAGE }
    val canExpand = shouldShowMemoCardExpand(bodyHeightPx, collapsedHeightPx, shouldShowExpand, hasImages)
    val expansion =
        rememberMemoCardExpansion(
            timestamp = timestamp,
            hasImages = hasImages,
            isExpanded = isExpanded,
            shouldShowExpand = canExpand,
            processedContent = processedContent,
            collapsedSummary = collapsedSummary,
            onExpandedChange = onExpandedChange,
        )
    val clampBodyHeight =
        shouldClampMemoCardBody(
            requestedExpanded = expansion.requestedExpanded,
            hasImages = hasImages,
        )
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val dateTimeFormatter = remember(dateFormat, timeFormat) {
        DateTimeFormatter.ofPattern("$dateFormat $timeFormat")
    }
    val cardInteractionSource = remember { MutableInteractionSource() }
    val tapSequence = rememberMemoCardTapSequence(isDoubleTapEnabled = onDoubleClick != null)
    val textLongClick = onMenuClick?.let { menuClick ->
        {
            tapSequence.reset()
            haptic.longPress()
            menuClick()
        }
    }
    val effectiveOnClick =
        memoCardClickHandler(
            tapSequence = tapSequence,
            haptic = haptic,
            onDoubleClick = onDoubleClick,
            onSingleClick = {
                if (expandOnClick && canExpand) expansion.updateExpanded(!expansion.requestedExpanded)
                onClick()
            },
        )
    val interactionModifier = Modifier.rememberMemoCardInteractionModifier(
        allowFreeTextCopy = allowFreeTextCopy,
        cardInteractionSource = cardInteractionSource,
        onClick = effectiveOnClick,
        onLongClick = textLongClick,
    )

    MemoCardContainer(isPinned = isPinned, interactionSource = cardInteractionSource, modifier = modifier) {
        Column(modifier = interactionModifier.padding(MemoCardTokens.ContainerPadding)) {
            MemoCardHeader(
                timestamp = timestamp,
                dateTimeFormatter = dateTimeFormatter,
                isPinned = isPinned,
                isPending = isPending,
                onMenuClick = onMenuClick,
                haptic = haptic,
                modifier = menuButtonModifier,
                menuContent = menuContent,
            )
            Spacer(modifier = Modifier.height(MemoCardTokens.HeaderBottomSpacing))
            MemoCardBody(
                processedContent = processedContent,
                shouldShowExpand = canExpand,
                isCollapsedPreview = expansion.isCollapsedPreview,
                collapsedPreviewMode = expansion.collapsedPreviewMode,
                collapsedSummary = collapsedSummary,
                isExpanded = expansion.bodyExpanded,
                clampHeight = clampBodyHeight,
                state =
                MemoCardBodyState(
                    renderDocument = renderDocument,
                    onBodyHeightChanged = { bodyHeightPx = it },
                    presentationPlan = presentationPlan,
                    allowFreeTextCopy = allowFreeTextCopy,
                    onBodyClick = effectiveOnClick,
                    onLongClick = textLongClick,
                    onTodoClick = onTodoClick,
                    onImageClick = onImageClick,
                    mediaPresentationResolver = mediaPresentationResolver,
                    mediaContent = mediaContent,
                ),
            )
            MemoCardFooter(
                tags = tags,
                reminders = reminders,
                shouldShowExpand = canExpand,
                isExpanded = expansion.requestedExpanded,
                haptic = haptic,
                onTagClick = onTagClick,
                onToggleExpanded = {
                    tapSequence.reset()
                    expansion.updateExpanded(!expansion.requestedExpanded)
                },
                onReminderClick = onReminderClick,
            )
        }
    }
}

@Composable
private fun rememberMemoCardTapSequence(isDoubleTapEnabled: Boolean): MemoCardTapSequence {
    val viewConfiguration = LocalViewConfiguration.current
    return remember(viewConfiguration, isDoubleTapEnabled) {
        MemoCardTapSequence(viewConfiguration.doubleTapMinTimeMillis, viewConfiguration.doubleTapTimeoutMillis)
    }
}

/**
 * One card owns the tap sequence across summary/full-body replacement. Framework double-tap
 * confirmation must not delay the first expansion or let the second tap collapse it again.
 */
private fun memoCardClickHandler(
    tapSequence: MemoCardTapSequence,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    onDoubleClick: (() -> Unit)?,
    onSingleClick: () -> Unit,
): () -> Unit = {
    haptic.medium()
    val tap = if (onDoubleClick == null) MemoCardTap.Click else tapSequence.release(SystemClock.uptimeMillis())
    when (tap) {
        MemoCardTap.Click -> onSingleClick()
        MemoCardTap.Edit -> onDoubleClick?.invoke()
    }
}

private class MemoCardExpansion(
    val requestedExpanded: Boolean,
    val bodyExpanded: Boolean,
    val isCollapsedPreview: Boolean,
    val collapsedPreviewMode: MemoCardCollapsedPreviewMode,
    val updateExpanded: (Boolean) -> Unit,
)

@Composable
private fun rememberMemoCardExpansion(
    timestamp: Long,
    hasImages: Boolean,
    isExpanded: Boolean?,
    shouldShowExpand: Boolean,
    processedContent: String,
    collapsedSummary: String,
    onExpandedChange: ((Boolean) -> Unit)?,
): MemoCardExpansion {
    var internalExpanded by rememberSaveable(timestamp) { mutableStateOf(false) }
    val requestedExpanded = isExpanded ?: internalExpanded
    val updateExpanded: (Boolean) -> Unit = { expanded ->
        if (isExpanded == null) internalExpanded = expanded
        onExpandedChange?.invoke(expanded)
    }
    val bodyExpanded = hasImages || !shouldShowExpand || requestedExpanded
    val isCollapsedPreview = !bodyExpanded
    return MemoCardExpansion(
        requestedExpanded = requestedExpanded,
        bodyExpanded = bodyExpanded,
        isCollapsedPreview = isCollapsedPreview,
        collapsedPreviewMode =
        resolveMemoCardCollapsedPreviewMode(
            isCollapsedPreview = isCollapsedPreview,
            hasProcessedContent = processedContent.isNotBlank(),
            collapsedSummary = collapsedSummary,
        ),
        updateExpanded = updateExpanded,
    )
}

@Composable
private fun MemoCardPendingBadge() {
    Surface(
        shape = MemoCardTokens.PinnedBadgeShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(Res.string.memo_card_pending),
            style = MaterialTheme.typography.labelMedium,
            modifier =
            Modifier.padding(
                horizontal = MemoCardTokens.PinnedBadgeHorizontalPadding,
                vertical = MemoCardTokens.PinnedBadgeVerticalPadding,
            ),
        )
    }
}

private fun Modifier.rememberMemoCardInteractionModifier(
    allowFreeTextCopy: Boolean,
    cardInteractionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = if (allowFreeTextCopy) {
    clip(MemoCardTokens.ContainerShape)
} else {
    this
        .clip(MemoCardTokens.ContainerShape)
        .combinedClickable(
            interactionSource = cardInteractionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
}

@Composable
private fun MemoCardHeader(
    timestamp: Long,
    dateTimeFormatter: DateTimeFormatter,
    isPinned: Boolean,
    isPending: Boolean,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeStr,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isPending) {
                Spacer(modifier = Modifier.width(AppSpacing.Small))
                MemoCardPendingBadge()
            }
        }
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
    val componentMotion = LocalLomoMotion.current.component
    Row(
        horizontalArrangement = Arrangement.spacedBy(MemoCardTokens.HeaderActionSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = isPinned,
            enter =
            fadeIn(componentMotion.fastEffectsSpec()) +
                scaleIn(initialScale = 0.85f, animationSpec = componentMotion.fastSpatialSpec()),
            exit =
            fadeOut(componentMotion.fastEffectsSpec()) +
                scaleOut(targetScale = 0.85f, animationSpec = componentMotion.fastSpatialSpec()),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MemoCardTokens.PinnedBadgeShape,
            ) {
                Row(
                    modifier =
                    Modifier.padding(
                        horizontal = MemoCardTokens.PinnedBadgeHorizontalPadding,
                        vertical = MemoCardTokens.PinnedBadgeVerticalPadding,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(MemoCardTokens.PinnedBadgeSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LomoIcon(
                        icon = LomoIcons.KeepFilled,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(MemoCardTokens.PinnedIconSize),
                    )
                    Text(
                        text = stringResource(Res.string.memo_pinned_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        Box {
            if (onMenuClick != null) {
                val menuButtonSize = androidx.compose.ui.unit.DpSize(28.dp, 28.dp)
                LomoIconButton(
                    onClick = {
                        haptic.medium()
                        onMenuClick()
                    },
                    modifier =
                    Modifier
                        .lomoCompactInteractiveHeight(menuButtonSize.height)
                        .size(menuButtonSize)
                        .then(modifier),
                ) {
                    LomoIcon(
                        LomoIcons.MoreVert,
                        contentDescription = stringResource(Res.string.cd_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
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
    shouldShowExpand: Boolean,
    isCollapsedPreview: Boolean,
    collapsedPreviewMode: MemoCardCollapsedPreviewMode,
    collapsedSummary: String,
    isExpanded: Boolean,
    clampHeight: Boolean,
    state: MemoCardBodyState,
) {
    val layoutMotion = LocalLomoMotion.current.layout
    val bodyTransitionMode = resolveMemoCardBodyTransitionMode(shouldShowExpand = shouldShowExpand)

    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec =
                layoutMotion.defaultSpatialSpec(),
            )
            .heightIn(max = if (clampHeight) MemoCardTokens.CollapsedBodyMaxHeight else Dp.Infinity)
            .clipToBounds(),
    ) {
        MemoCardBodyContent(
            collapsedPreviewMode = collapsedPreviewMode,
            collapsedSummary = collapsedSummary,
            processedContent = processedContent,
            isExpanded = isExpanded,
            isCollapsedPreview = isCollapsedPreview,
            bodyTransitionMode = bodyTransitionMode,
            state = state,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoCardFooter(
    tags: ImmutableList<String>,
    reminders: ImmutableList<ReminderMarker>,
    shouldShowExpand: Boolean,
    isExpanded: Boolean,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    onTagClick: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onReminderClick: (ReminderMarker) -> Unit,
) {
    val hasMetadataPills = tags.isNotEmpty() || reminders.isNotEmpty()
    if (!shouldShowExpand && !hasMetadataPills) {
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = MemoCardTokens.FooterTopPadding),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasMetadataPills) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement =
                Arrangement.spacedBy(
                    space = MemoCardTokens.FooterItemSpacing,
                    alignment = Alignment.Start,
                ),
                verticalArrangement = Arrangement.spacedBy(MemoCardTokens.FooterItemSpacing),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                MemoCardTagPills(tags = tags, haptic = haptic, onTagClick = onTagClick)
                MemoCardReminderPills(
                    reminders = reminders,
                    haptic = haptic,
                    onReminderClick = onReminderClick,
                )
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (shouldShowExpand) {
            MemoCardExpandToggle(
                isExpanded = isExpanded,
                haptic = haptic,
                onToggleExpanded = onToggleExpanded,
            )
        }
    }
}

@Composable
private fun MemoCardExpandToggle(
    isExpanded: Boolean,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    onToggleExpanded: () -> Unit,
) {
    val label = if (isExpanded) Res.string.cd_collapse else Res.string.cd_expand
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = LocalLomoMotion.current.component.fastSpatialSpec(),
        label = "MemoCardExpandChevron",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MemoCardTokens.MetadataPillShape,
        modifier =
        Modifier
            .lomoCompactInteractiveHeight(MemoCardTokens.ExpandToggleVisualHeight)
            .clip(MemoCardTokens.MetadataPillShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = {
                    haptic.medium()
                    onToggleExpanded()
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MemoCardTokens.ExpandToggleIconSpacing),
            modifier =
            Modifier
                .height(MemoCardTokens.ExpandToggleVisualHeight)
                .padding(horizontal = MemoCardTokens.ExpandToggleHorizontalPadding),
        ) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            LomoIcon(
                icon = LomoIcons.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier =
                Modifier
                    .size(MemoCardTokens.ExpandChevronSize)
                    .graphicsLayer { rotationZ = chevronRotation },
            )
        }
    }
}
