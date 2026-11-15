package com.lomo.ui.component.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.info_characters
import com.lomo.ui.generated.resources.info_created
import com.lomo.ui.icon.LomoIcon
import org.jetbrains.compose.resources.stringResource

/** Top row of compact icon-over-label tiles sharing the width equally. */
@Composable
internal fun ActionTileRow(
    actions: List<ActionItemUi>,
    onTileCenterMeasured: (String, Offset) -> Unit,
    onPerform: (ActionItemUi) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ActionSheetTokens.TileSpacing),
    ) {
        actions.forEach { action ->
            ActionTile(
                action = action,
                onClick = { onPerform(action) },
                modifier =
                Modifier
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        val localCenter = Offset(coords.size.width / 2f, coords.size.height / 2f)
                        onTileCenterMeasured(action.label, coords.localToScreen(localCenter))
                    },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionTile(action: ActionItemUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ActionSheetTokens.tileColors(MaterialTheme.colorScheme, action)
    LomoFilledTonalButton(
        onClick = onClick,
        shapes = ActionSheetTokens.tileShapes(),
        colors =
        ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.container,
            contentColor = colors.content,
        ),
        contentPadding = PaddingValues(horizontal = ActionSheetTokens.TileContentPadding),
        modifier =
        modifier
            .height(ActionSheetTokens.TileHeight)
            .benchmarkAnchor(action.benchmarkTag),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LomoIcon(
                icon = action.icon,
                contentDescription = null,
                modifier = Modifier.size(ActionSheetTokens.TileIconSize),
            )
            Spacer(modifier = Modifier.height(ActionSheetTokens.TileIconLabelSpacing))
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Side-by-side filled capsules of equal height aligned under the top tiles.
 */
@Composable
internal fun ActionCapsuleRow(actions: List<ActionItemUi>, onPerform: (ActionItemUi) -> Unit) {
    val isInline = actions.size <= ActionSheetTokens.InlineCapsuleMaxCount
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ActionSheetTokens.TileSpacing),
    ) {
        actions.forEach { action ->
            ActionCapsule(
                action = action,
                isInline = isInline,
                onClick = { onPerform(action) },
                modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionCapsule(action: ActionItemUi, isInline: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ActionSheetTokens.capsuleColors(MaterialTheme.colorScheme, action)
    LomoFilledTonalButton(
        onClick = onClick,
        shapes = ActionSheetTokens.capsuleShapes(),
        colors =
        ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.container,
            contentColor = colors.content,
        ),
        contentPadding = PaddingValues(ActionSheetTokens.CapsuleContentPadding),
        modifier =
        modifier
            .heightIn(min = ActionSheetTokens.CapsuleMinHeight)
            .benchmarkAnchor(action.benchmarkTag),
    ) {
        if (isInline) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LomoIcon(
                    icon = action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(ActionSheetTokens.CapsuleIconSize),
                )
                Spacer(modifier = Modifier.width(ActionSheetTokens.CapsuleIconLabelSpacing))
                CapsuleLabel(label = action.label, modifier = Modifier.weight(1f, fill = false))
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LomoIcon(
                    icon = action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(ActionSheetTokens.CapsuleIconSize),
                )
                CapsuleLabel(label = action.label)
            }
        }
    }
}

@Composable
private fun CapsuleLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = ActionSheetTokens.CapsuleLabelMaxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Restored v1.6.2-inspired bottom metadata card showing created time and character count. */
@Composable
internal fun MemoInfoCard(state: MemoMenuState, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = ActionSheetTokens.InfoCardShape,
        color = ActionSheetTokens.infoCardColor(colorScheme),
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ActionSheetTokens.InfoCardHorizontalPadding,
                    vertical = ActionSheetTokens.InfoCardVerticalPadding,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = stringResource(Res.string.info_created),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.createdTime,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(ActionSheetTokens.TileSpacing))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(Res.string.info_characters),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.wordCount.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}
