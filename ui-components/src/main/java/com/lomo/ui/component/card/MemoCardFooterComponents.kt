package com.lomo.ui.component.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.domain.model.ReminderMarker
import com.lomo.ui.R
import com.lomo.ui.theme.AppShapes
import kotlinx.collections.immutable.ImmutableList
import java.time.format.DateTimeFormatter

@Composable
internal fun MemoCardTagPills(
    tags: ImmutableList<String>,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    onTagClick: (String) -> Unit,
) {
    tags.forEach { tag ->
        val colors = memoTagPillColors(MaterialTheme.colorScheme)
        Surface(
            onClick = {
                haptic.medium()
                onTagClick(tag)
            },
            shape = AppShapes.Small,
            color = colors.containerColor,
            modifier = Modifier.height(24.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = "#$tag",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.contentColor,
                )
            }
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
    val doneLabel = stringResource(R.string.reminder_done)
    reminders.forEach { reminder ->
        val isExhausted = reminder.isExhausted
        val colors = memoReminderPillColors(isExhausted = isExhausted, colorScheme = MaterialTheme.colorScheme)

        Surface(
            onClick = {
                if (!isExhausted) {
                    haptic.medium()
                    onReminderClick(reminder)
                }
            },
            enabled = !isExhausted,
            shape = AppShapes.Small,
            color = colors.containerColor,
            modifier = Modifier.height(24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Alarm,
                    contentDescription = null,
                    tint = colors.contentColor,
                    modifier = Modifier.size(12.dp),
                )
                val displayText = ReminderDisplayFormatter.format(reminder, formatter, doneLabel)
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.contentColor,
                )
            }
        }
    }
}
