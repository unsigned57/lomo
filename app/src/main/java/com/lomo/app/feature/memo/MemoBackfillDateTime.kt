package com.lomo.app.feature.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.ui.component.picker.ExpressiveDatePickerSurface
import com.lomo.ui.component.picker.ExpressivePickerDialog
import com.lomo.ui.component.picker.ExpressiveTimePickerSurface
import com.lomo.ui.component.picker.SecondsWheelPicker
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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

    ExpressivePickerDialog(
        title = stringResource(R.string.memo_backfill_title),
        confirmLabel = stringResource(R.string.memo_backfill_action_apply),
        confirmEnabled = selectedDate != null,
        onConfirm = {
            val date = selectedDate ?: return@ExpressivePickerDialog
            onConfirm(
                combineMemoBackfillDateTimeMillis(
                    date = date,
                    time = LocalTime.of(timePickerState.hour, timePickerState.minute, selectedSecond),
                ),
            )
        },
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ExpressiveDatePickerSurface(state = datePickerState)
            ExpressiveTimePickerSurface(
                state = timePickerState,
                secondsSlot = {
                    Text(
                        text = stringResource(R.string.memo_backfill_seconds_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecondsWheelPicker(
                        value = selectedSecond,
                        onValueChange = { selectedSecond = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
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
