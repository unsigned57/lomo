package com.lomo.ui.component.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.info_characters
import com.lomo.ui.generated.resources.info_created
import org.jetbrains.compose.resources.stringResource

/** Top row of large icon-over-label tiles sharing the width equally. */
@Composable
internal fun ActionTileRow(actions: List<ActionItemUi>, onPerform: (ActionItemUi) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ActionSheetTokens.TileSpacing),
    ) {
        actions.forEach { action ->
            ActionTile(
                action = action,
                onClick = { onPerform(action) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActionTile(action: ActionItemUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ActionSheetTokens.tileColors(MaterialTheme.colorScheme, action)
    Surface(
        onClick = onClick,
        shape = ActionSheetTokens.TileShape,
        color = colors.container,
        contentColor = colors.content,
        modifier =
        modifier
            .height(ActionSheetTokens.TileHeight)
            .benchmarkAnchor(action.benchmarkTag),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ActionSheetTokens.TileContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(ActionSheetTokens.TileIconSize),
            )
            Spacer(modifier = Modifier.height(ActionSheetTokens.TileIconLabelSpacing))
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Borderless icon + label list row. */
@Composable
internal fun ActionListRow(action: ActionItemUi, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val iconTint = if (action.isDestructive) colorScheme.error else colorScheme.onSurfaceVariant
    val labelColor = if (action.isDestructive) colorScheme.error else colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = ActionSheetTokens.RowShape,
        color = Color.Transparent,
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = ActionSheetTokens.RowMinHeight)
            .benchmarkAnchor(action.benchmarkTag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ActionSheetTokens.RowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(ActionSheetTokens.RowIconSize),
            )
            Spacer(modifier = Modifier.width(ActionSheetTokens.RowIconLabelSpacing))
            Text(
                text = action.label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ActionRowDivider() {
    HorizontalDivider(
        modifier =
        Modifier.padding(
            horizontal = ActionSheetTokens.RowHorizontalPadding,
            vertical = ActionSheetTokens.DividerVerticalPadding,
        ),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * Side-by-side filled capsules of equal height. Labels wrap instead of truncating so long
 * translations stay readable at any font scale.
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

@Composable
private fun ActionCapsule(action: ActionItemUi, isInline: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ActionSheetTokens.capsuleColors(MaterialTheme.colorScheme, action)
    Surface(
        onClick = onClick,
        shape = ActionSheetTokens.CapsuleShape,
        color = colors.container,
        contentColor = colors.content,
        modifier =
        modifier
            .heightIn(min = ActionSheetTokens.CapsuleMinHeight)
            .benchmarkAnchor(action.benchmarkTag),
    ) {
        if (isInline) {
            Row(
                modifier = Modifier.padding(ActionSheetTokens.CapsuleContentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(ActionSheetTokens.CapsuleIconSize),
                )
                Spacer(modifier = Modifier.width(ActionSheetTokens.CapsuleIconLabelSpacing))
                CapsuleLabel(label = action.label, modifier = Modifier.weight(1f, fill = false))
            }
        } else {
            Column(
                modifier = Modifier.padding(ActionSheetTokens.CapsuleContentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = action.icon,
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
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = ActionSheetTokens.CapsuleLabelMaxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Container-less creation time and character count line closing the sheet. */
@Composable
internal fun MemoMetaRow(state: MemoMenuState, modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = ActionSheetTokens.RowHorizontalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaItem(label = stringResource(Res.string.info_created), value = state.createdTime)
        MetaItem(label = stringResource(Res.string.info_characters), value = "${state.wordCount}")
    }
}

@Composable
private fun MetaItem(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(ActionSheetTokens.MetaLabelValueSpacing))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
