package com.lomo.app.feature.memo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val BACKFILL_SECONDS_INPUT_MAX_LENGTH = 2
private const val BACKFILL_SECONDS_MIN = 0
private const val BACKFILL_SECONDS_MAX = 59

@Stable
internal class MemoBackfillSelectionState {
    var timestampMillis: Long? by mutableStateOf(null)
        private set

    fun setTimestampForCreate(
        timestampMillis: Long,
        isEditingExistingMemo: Boolean,
    ) {
        if (!isEditingExistingMemo) {
            this.timestampMillis = timestampMillis
        }
    }

    fun timestampMillisForCreateSubmit(isEditingExistingMemo: Boolean): Long? =
        if (isEditingExistingMemo) {
            null
        } else {
            timestampMillis
        }

    fun clear() {
        timestampMillis = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoBackfillDateTimeDialog(
    initialTimestampMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialDateTime =
        remember(initialTimestampMillis) {
            initialTimestampMillis
                ?.let { timestampMillis ->
                    Instant
                        .ofEpochMilli(timestampMillis)
                        .atZone(ZoneId.systemDefault())
                }
                ?: ZonedDateTime.now()
        }
    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = initialDateTime.toLocalDate().toMemoBackfillDatePickerMillis(),
        )
    val timePickerState =
        rememberTimePickerState(
            initialHour = initialDateTime.hour,
            initialMinute = initialDateTime.minute,
            is24Hour = true,
        )
    var selectedSecond by remember(initialTimestampMillis) { mutableIntStateOf(initialDateTime.second) }
    val selectedDate = datePickerState.selectedDateMillis?.toMemoBackfillLocalDate()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            FilledTonalButton(
                enabled = selectedDate != null,
                onClick = {
                    val date = selectedDate ?: return@FilledTonalButton
                    onConfirm(
                        combineMemoBackfillDateTimeMillis(
                            date = date,
                            time = LocalTime.of(timePickerState.hour, timePickerState.minute, selectedSecond),
                        ),
                    )
                },
            ) {
                Text(text = stringResource(R.string.memo_backfill_action_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            DatePicker(
                state = datePickerState,
                showModeToggle = false,
                title = {
                    Text(
                        text = stringResource(R.string.memo_backfill_title),
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                    )
                },
            )
            TimePicker(
                state = timePickerState,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            OutlinedTextField(
                value = selectedSecond.toString().padStart(BACKFILL_SECONDS_INPUT_MAX_LENGTH, '0'),
                onValueChange = { value ->
                    value
                        .filter(Char::isDigit)
                        .take(BACKFILL_SECONDS_INPUT_MAX_LENGTH)
                        .toIntOrNull()
                        ?.takeIf { second -> second in BACKFILL_SECONDS_MIN..BACKFILL_SECONDS_MAX }
                        ?.let { second -> selectedSecond = second }
                },
                label = { Text(text = stringResource(R.string.memo_backfill_seconds_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
            )
        }
    }
}

internal fun LocalDate.toMemoBackfillDatePickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

internal fun Long.toMemoBackfillLocalDate(): LocalDate =
    Instant
        .ofEpochMilli(this)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()

internal fun combineMemoBackfillDateTimeMillis(
    date: LocalDate,
    time: LocalTime,
    zone: ZoneId = ZoneId.systemDefault(),
): Long =
    date
        .atTime(time)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

internal fun formatMemoBackfillBadgeText(
    timestampMillis: Long,
    dateFormat: String,
    timeFormat: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    DateTimeFormatter
        .ofPattern("$dateFormat ${timeFormat.withSecondsPattern()}")
        .withZone(zone)
        .format(Instant.ofEpochMilli(timestampMillis))

private fun String.withSecondsPattern(): String =
    if (contains('s')) {
        this
    } else if (contains(" a")) {
        replace(" a", ":ss a")
    } else {
        "$this:ss"
    }

internal fun shouldOpenMemoBackfillDialog(isEditingExistingMemo: Boolean): Boolean =
    !isEditingExistingMemo
