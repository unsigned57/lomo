package com.lomo.ui.component.card

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.lomo.domain.model.ReminderMarker
import com.lomo.ui.component.common.lomoCompactInteractiveHeight
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.reminder_done
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource
import java.time.format.DateTimeFormatter

/**
 * Renders a visually compact metadata pill ([MemoCardTokens.MetadataPillVisualHeight]) while
 * extending only the vertical touch bounds to 48dp so horizontal pill spacing in a [FlowRow]
 * is never inflated to 48dp minimum widths.
 */
@Composable
private fun MemoMetadataPillCell(
    onClick: () -> Unit,
    enabled: Boolean,
    colors: MemoMetadataPillColors,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        color = colors.containerColor,
        shape = MemoCardTokens.MetadataPillShape,
        modifier =
        Modifier
            .lomoCompactInteractiveHeight(MemoCardTokens.MetadataPillVisualHeight)
            .clip(MemoCardTokens.MetadataPillShape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
            Modifier
                .height(MemoCardTokens.MetadataPillVisualHeight)
                .padding(horizontal = MemoCardTokens.MetadataPillHorizontalPadding),
        ) {
            content()
        }
    }
}

@Composable
internal fun MemoCardTagPills(
    tags: ImmutableList<String>,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    onTagClick: (String) -> Unit,
) {
    tags.forEach { tag ->
        val colors = memoTagPillColors(MaterialTheme.colorScheme)
        MemoMetadataPillCell(
            onClick = {
                haptic.medium()
                onTagClick(tag)
            },
            enabled = true,
            colors = colors,
        ) {
            Text(
                text = "#$tag",
                style = MaterialTheme.typography.labelMedium,
                color = colors.contentColor,
            )
        }
    }
}

@Composable
internal fun MemoCardReminderPills(
    reminders: ImmutableList<ReminderMarker>,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    onReminderClick: (ReminderMarker) -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    val doneLabel = stringResource(Res.string.reminder_done)
    reminders.forEach { reminder ->
        val isExhausted = reminder.isExhausted
        val colors = memoReminderPillColors(isExhausted = isExhausted, colorScheme = MaterialTheme.colorScheme)
        MemoMetadataPillCell(
            onClick = {
                haptic.medium()
                onReminderClick(reminder)
            },
            enabled = !isExhausted,
            colors = colors,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MemoCardTokens.PinnedBadgeSpacing),
            ) {
                LomoIcon(
                    icon = LomoIcons.Notifications,
                    contentDescription = null,
                    tint = colors.contentColor,
                    modifier = Modifier.size(MemoCardTokens.PinnedIconSize),
                )
                val displayText = ReminderDisplayFormatter.format(reminder, formatter, doneLabel)
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.contentColor,
                )
            }
        }
    }
}
