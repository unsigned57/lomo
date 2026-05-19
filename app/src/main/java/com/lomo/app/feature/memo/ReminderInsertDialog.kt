package com.lomo.app.feature.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.ReminderMarker
import com.lomo.ui.theme.AppSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private const val REMINDER_REPEAT_MIN = 1
private const val REMINDER_REPEAT_MAX = 9

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderInsertDialog(
    onDismiss: () -> Unit,
    onConfirm: (token: String) -> Unit,
) {
    val now = remember { LocalDateTime.now().plusMinutes(5) }
    val zone = remember { ZoneId.systemDefault() }
    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis =
                now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    val timePickerState =
        rememberTimePickerState(
            initialHour = now.hour,
            initialMinute = now.minute,
            is24Hour = true,
        )
    var repeatCount by remember { mutableIntStateOf(REMINDER_REPEAT_MIN) }
    var page by remember { mutableStateOf(ReminderDialogPage.Date) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.reminder_dialog_title)) },
        text = {
            when (page) {
                ReminderDialogPage.Date -> DatePicker(state = datePickerState, showModeToggle = false)
                ReminderDialogPage.Time -> TimePicker(state = timePickerState)
                ReminderDialogPage.Repeat -> RepeatStepper(value = repeatCount, onValueChanged = { repeatCount = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (page) {
                        ReminderDialogPage.Date -> page = ReminderDialogPage.Time
                        ReminderDialogPage.Time -> page = ReminderDialogPage.Repeat
                        ReminderDialogPage.Repeat -> {
                            val dateMillis = datePickerState.selectedDateMillis
                            if (dateMillis != null) {
                                val localDate =
                                    Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
                                val token =
                                    buildReminderToken(
                                        date = localDate,
                                        hour = timePickerState.hour,
                                        minute = timePickerState.minute,
                                        repeatCount = repeatCount,
                                    )
                                onConfirm(token)
                            }
                        }
                    }
                },
            ) {
                Text(
                    text =
                        stringResource(
                            if (page == ReminderDialogPage.Repeat) R.string.action_confirm else R.string.action_next,
                        ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

private enum class ReminderDialogPage { Date, Time, Repeat }

internal fun buildReminderInsertionValue(
    inputValue: TextFieldValue,
    token: String,
): TextFieldValue {
    val cursor = inputValue.selection.start.coerceIn(0, inputValue.text.length)
    val prefix = inputValue.text.substring(0, cursor)
    val suffix = inputValue.text.substring(cursor)
    val needsLeadingSpace = prefix.isNotEmpty() && !prefix.last().isWhitespace()
    val insertion = if (needsLeadingSpace) " $token" else token
    val newText = prefix + insertion + suffix
    val cursorTarget = cursor + insertion.length
    return TextFieldValue(newText, TextRange(cursorTarget))
}

private fun buildReminderToken(
    date: LocalDate,
    hour: Int,
    minute: Int,
    repeatCount: Int,
): String =
    ReminderMarker.canonicalToken(
        dueAt = LocalDateTime.of(date, LocalTime.of(hour, minute)),
        repeatCount = repeatCount,
        firedCount = 0,
        done = false,
    )

@Composable
private fun RepeatStepper(
    value: Int,
    onValueChanged: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(AppSpacing.Medium),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
    ) {
        Text(
            text = stringResource(R.string.reminder_dialog_repeat_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            FilledTonalIconButton(
                onClick = { onValueChanged((value - 1).coerceAtLeast(REMINDER_REPEAT_MIN)) },
                enabled = value > REMINDER_REPEAT_MIN,
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = null)
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(AppSpacing.Small),
                modifier = Modifier.size(width = 64.dp, height = 48.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$value",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            FilledTonalIconButton(
                onClick = { onValueChanged((value + 1).coerceAtMost(REMINDER_REPEAT_MAX)) },
                enabled = value < REMINDER_REPEAT_MAX,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
            }
        }
        Text(
            text = stringResource(R.string.reminder_dialog_repeat_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
