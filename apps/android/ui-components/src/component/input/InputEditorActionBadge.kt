package com.lomo.ui.component.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.action_cancel
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.theme.LocalLomoMotion
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun InputEditorActionBadgesContent(
    badges: ImmutableList<InputEditorActionBadge>,
    onEditorCommand: (InputEditorCommand) -> Unit,
) {
    val motionScheme = LocalLomoMotion.current.layout
    AnimatedVisibility(
        visible = badges.isNotEmpty(),
        enter = expandVertically(animationSpec = motionScheme.defaultSpatialSpec()) + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
        ) {
            badges.forEach { badge -> InputEditorActionBadgePill(badge, onEditorCommand) }
        }
    }
}

@Composable
private fun InputEditorActionBadgePill(badge: InputEditorActionBadge, onEditorCommand: (InputEditorCommand) -> Unit) {
    val cancelLabel = stringResource(Res.string.action_cancel)
    Surface(
        shape = InputSheetTokens.ActionBadgeShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .heightIn(min = InputSheetTokens.HeaderActionClusterHeight)
            .clip(InputSheetTokens.ActionBadgeShape)
            .benchmarkAnchor(badge.benchmarkTag)
            .clickable(onClickLabel = cancelLabel) { onEditorCommand(badge.command) },
    ) {
        Row(
            modifier = Modifier.padding(InputSheetTokens.ActionBadgeContentPadding),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LomoIcon(
                icon = badge.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(InputSheetTokens.ActionBadgeIconSize),
            )
            Text(
                text = badge.text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
