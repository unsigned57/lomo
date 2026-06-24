package com.lomo.app.feature.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.ui.component.picker.ExpressiveDatePickerSurface
import com.lomo.ui.component.picker.ExpressivePickerStep
import com.lomo.ui.component.picker.ExpressiveSteppedPickerDialog
import com.lomo.ui.component.picker.ExpressiveTimePickerSurface
import com.lomo.ui.component.picker.SecondsWheelPicker
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.collections.immutable.persistentListOf

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

    ExpressiveSteppedPickerDialog(
        title = stringResource(R.string.memo_backfill_title),
        advanceLabel = stringResource(R.string.action_next),
        confirmLabel = stringResource(R.string.memo_backfill_action_apply),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = {
            val date = selectedDate ?: return@ExpressiveSteppedPickerDialog
            onConfirm(
                combineMemoBackfillDateTimeMillis(
                    date = date,
                    time = LocalTime.of(timePickerState.hour, timePickerState.minute, selectedSecond),
                ),
            )
        },
        onDismiss = onDismiss,
        steps =
            persistentListOf(
                ExpressivePickerStep(confirmEnabled = selectedDate != null) {
                    ExpressiveDatePickerSurface(state = datePickerState)
                },
                ExpressivePickerStep {
                    ExpressiveTimePickerSurface(state = timePickerState)
                },
                ExpressivePickerStep {
                    MemoBackfillSecondsPage(
                        selectedSecond = selectedSecond,
                        onSecondChange = { selectedSecond = it },
                    )
                },
            ),
    )
}

@Composable
private fun MemoBackfillSecondsPage(
    selectedSecond: Int,
    onSecondChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.memo_backfill_seconds_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Start),
        )
        SecondsWheelPicker(
            value = selectedSecond,
            onValueChange = onSecondChange,
            modifier = Modifier.fillMaxWidth(),
        )
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
