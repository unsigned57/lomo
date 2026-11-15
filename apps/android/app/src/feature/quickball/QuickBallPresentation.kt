package com.lomo.app.feature.quickball

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.QuickBallAction
import com.lomo.domain.model.QuickBallConfig
import com.lomo.domain.model.QuickBallGesture
import com.lomo.ui.component.common.LomoButtonDefaults
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.component.common.LomoModalBottomSheet
import com.lomo.ui.component.common.LomoSheetTokens
import com.lomo.ui.component.common.rememberModalSheetPresentation
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIconSpec
import com.lomo.ui.icon.LomoIcons
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

internal fun QuickBallAction.icon(): LomoIconSpec = when (this) {
    QuickBallAction.NEW_MEMO -> LomoIcons.Add
    QuickBallAction.RECORD -> LomoIcons.Mic
    QuickBallAction.LONG_TEXT -> LomoIcons.Article
    QuickBallAction.SCROLL_TOP -> LomoIcons.KeyboardArrowUp
    QuickBallAction.SEARCH -> LomoIcons.Search
    QuickBallAction.FILTER -> LomoIcons.FilterList
    QuickBallAction.CLEAR_FILTER -> LomoIcons.FilterListOff
    QuickBallAction.ACTION_MENU -> LomoIcons.MoreHoriz
    QuickBallAction.DAILY_REVIEW -> LomoIcons.History
    QuickBallAction.GALLERY -> LomoIcons.PhotoLibrary
    QuickBallAction.NONE -> LomoIcons.Block
}

internal fun QuickBallGesture.icon(): LomoIconSpec = when (this) {
    QuickBallGesture.TAP -> LomoIcons.TouchApp
    QuickBallGesture.LONG_PRESS -> LomoIcons.RadioButtonChecked
    QuickBallGesture.UP -> LomoIcons.KeyboardArrowUp
    QuickBallGesture.DOWN -> LomoIcons.KeyboardArrowDown
    QuickBallGesture.LEFT -> LomoIcons.KeyboardArrowLeft
    QuickBallGesture.RIGHT -> LomoIcons.KeyboardArrowRight
}

internal fun quickBallIcons(config: QuickBallConfig): ImmutableMap<QuickBallGesture, LomoIconSpec> =
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

    LomoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = presentation.sheetState,
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
            shape = AppShapes.Medium,
        ) {
            LomoIcon(
                icon = LomoIcons.TouchApp,
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
                    LomoIcon(
                        icon = selectedAction.icon(),
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
            else -> LomoSheetTokens.tileSurfaceColor(colorScheme)
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
        shapes = LomoButtonDefaults.tileShapes(),
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
                shape = AppShapes.Medium,
                color = badgeColor,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LomoIcon(
                        icon = action.icon(),
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
                        LomoIcon(
                            icon = LomoIcons.Check,
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
