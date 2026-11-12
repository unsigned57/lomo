package com.lomo.app.feature.quickball

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FilterListOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.QuickBallAction
import com.lomo.domain.model.QuickBallConfig
import com.lomo.domain.model.QuickBallGesture
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.component.common.rememberModalSheetPresentation
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap

@StringRes
internal fun QuickBallAction.labelRes(): Int = when (this) {
    QuickBallAction.NEW_MEMO -> R.string.quick_ball_new_memo
    QuickBallAction.RECORD -> R.string.quick_ball_record
    QuickBallAction.LONG_TEXT -> R.string.quick_ball_long_text
    QuickBallAction.SCROLL_TOP -> R.string.quick_ball_scroll_top
    QuickBallAction.SEARCH -> R.string.quick_ball_search
    QuickBallAction.FILTER -> R.string.quick_ball_filter
    QuickBallAction.CLEAR_FILTER -> R.string.quick_ball_clear_filter
    QuickBallAction.ACTION_MENU -> R.string.quick_ball_action_menu
    QuickBallAction.DAILY_REVIEW -> R.string.quick_ball_daily_review
    QuickBallAction.GALLERY -> R.string.quick_ball_gallery
    QuickBallAction.NONE -> R.string.quick_ball_none
}

@StringRes
internal fun QuickBallGesture.labelRes(): Int = when (this) {
    QuickBallGesture.TAP -> R.string.quick_ball_tap
    QuickBallGesture.LONG_PRESS -> R.string.quick_ball_long_press
    QuickBallGesture.UP -> R.string.quick_ball_up
    QuickBallGesture.DOWN -> R.string.quick_ball_down
    QuickBallGesture.LEFT -> R.string.quick_ball_left
    QuickBallGesture.RIGHT -> R.string.quick_ball_right
}

@Composable
internal fun quickBallLabels(config: QuickBallConfig): ImmutableMap<QuickBallGesture, String> =
    QuickBallGesture.entries.associateWith { gesture ->
        val action = stringResource(config.action(gesture).labelRes())
        if (gesture == QuickBallGesture.TAP) {
            action
        } else {
            stringResource(R.string.quick_ball_binding, stringResource(gesture.labelRes()), action)
        }
    }.toImmutableMap()

internal fun QuickBallAction.icon(): ImageVector = when (this) {
    QuickBallAction.NEW_MEMO -> Icons.Rounded.Add
    QuickBallAction.RECORD -> Icons.Rounded.Mic
    QuickBallAction.LONG_TEXT -> Icons.AutoMirrored.Rounded.Article
    QuickBallAction.SCROLL_TOP -> Icons.Rounded.KeyboardArrowUp
    QuickBallAction.SEARCH -> Icons.Rounded.Search
    QuickBallAction.FILTER -> Icons.Rounded.FilterList
    QuickBallAction.CLEAR_FILTER -> Icons.Rounded.FilterListOff
    QuickBallAction.ACTION_MENU -> Icons.Rounded.MoreHoriz
    QuickBallAction.DAILY_REVIEW -> Icons.Rounded.History
    QuickBallAction.GALLERY -> Icons.Rounded.PhotoLibrary
    QuickBallAction.NONE -> Icons.Rounded.Block
}

internal fun QuickBallGesture.icon(): ImageVector = when (this) {
    QuickBallGesture.TAP -> Icons.Rounded.TouchApp
    QuickBallGesture.LONG_PRESS -> Icons.Rounded.RadioButtonChecked
    QuickBallGesture.UP -> Icons.Rounded.KeyboardArrowUp
    QuickBallGesture.DOWN -> Icons.Rounded.KeyboardArrowDown
    QuickBallGesture.LEFT -> Icons.AutoMirrored.Rounded.KeyboardArrowLeft
    QuickBallGesture.RIGHT -> Icons.AutoMirrored.Rounded.KeyboardArrowRight
}

internal fun quickBallIcons(config: QuickBallConfig): ImmutableMap<QuickBallGesture, ImageVector> =
    QuickBallGesture.entries.associateWith { config.action(it).icon() }.toImmutableMap()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun QuickBallActionSheet(
    title: String,
    actions: ImmutableList<QuickBallAction>,
    selectedAction: QuickBallAction?,
    onSelect: (QuickBallAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val presentation = rememberModalSheetPresentation()
    val haptic = LocalAppHapticFeedback.current
    val positiveActions = actions.filter { it != QuickBallAction.NONE }
    val disableAction = actions.firstOrNull { it == QuickBallAction.NONE }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = presentation.sheetState,
        shape = AppShapes.ExtraLargeIncreased,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = AppSpacing.ExtraSmall,
        dragHandle = { QuickBallSheetDragHandle() },
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged(presentation::onContentMeasured)
                .padding(horizontal = AppSpacing.ScreenHorizontalPadding)
                .padding(bottom = AppSpacing.ExtraLarge)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            QuickBallSheetHeader(
                title = title,
                selectedAction = selectedAction,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
            ) {
                positiveActions.chunked(2).forEach { rowActions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                    ) {
                        rowActions.forEach { action ->
                            QuickBallActionTile(
                                action = action,
                                selected = action == selectedAction,
                                isSelectionMode = selectedAction != null,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    haptic.medium()
                                    onSelect(action)
                                },
                            )
                        }
                        if (rowActions.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (disableAction != null) {
                    QuickBallActionTile(
                        action = disableAction,
                        selected = disableAction == selectedAction,
                        isSelectionMode = selectedAction != null,
                        isDestructiveNeutral = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            haptic.medium()
                            onSelect(disableAction)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickBallSheetDragHandle() {
    Box(
        modifier =
        Modifier
            .padding(vertical = AppSpacing.Large)
            .width(AppSpacing.ExtraLarge)
            .size(width = AppSpacing.ExtraLarge, height = AppSpacing.ExtraSmall)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuickBallSheetHeader(title: String, selectedAction: QuickBallAction?) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(AppSpacing.MediumSmall),
        ) {
            Icon(
                imageVector = Icons.Rounded.TouchApp,
                contentDescription = null,
                modifier = Modifier.padding(AppSpacing.Small),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selectedAction != null) {
            Surface(
                shape = AppShapes.Full,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = selectedAction.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource(selectedAction.labelRes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuickBallActionTile(
    action: QuickBallAction,
    selected: Boolean,
    isSelectionMode: Boolean,
    modifier: Modifier = Modifier,
    isDestructiveNeutral: Boolean = false,
    onClick: () -> Unit,
) {
    val componentMotion = LocalLomoMotion.current.component
    val colorScheme = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue =
        when {
            selected && isDestructiveNeutral -> colorScheme.errorContainer
            selected -> colorScheme.secondaryContainer
            isDestructiveNeutral -> colorScheme.surfaceContainerLow
            else -> colorScheme.surfaceContainerHigh
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "QuickBallTileContainer",
    )
    val contentColor by animateColorAsState(
        targetValue =
        when {
            selected && isDestructiveNeutral -> colorScheme.onErrorContainer
            selected -> colorScheme.onSecondaryContainer
            isDestructiveNeutral -> colorScheme.onSurfaceVariant
            else -> colorScheme.onSurface
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "QuickBallTileContent",
    )
    val badgeColor by animateColorAsState(
        targetValue =
        when {
            selected && isDestructiveNeutral -> colorScheme.error.copy(alpha = 0.16f)
            selected -> colorScheme.primary.copy(alpha = 0.16f)
            else -> colorScheme.surfaceContainerHighest
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "QuickBallTileBadge",
    )

    LomoFilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shapes = ButtonShapes(AppShapes.LargeIncreased, RoundedCornerShape(12.dp)),
        colors =
        ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = badgeColor,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = action.icon(),
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = stringResource(action.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isSelectionMode && selected) {
                Surface(
                    shape = CircleShape,
                    color = contentColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.quick_ball_selected),
                            tint = contentColor,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}
