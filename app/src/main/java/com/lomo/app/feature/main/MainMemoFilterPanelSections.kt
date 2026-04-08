package com.lomo.app.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.R
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.ui.theme.AppSpacing
import java.time.LocalDate

@Composable
internal fun MainMemoFilterSheetContent(
    filter: MemoListFilter,
    hasDateFilter: Boolean,
    onSortOptionSelected: (MemoSortOption) -> Unit,
    onOpenStartDatePicker: () -> Unit,
    onOpenEndDatePicker: () -> Unit,
    onClearStartDate: () -> Unit,
    onClearEndDate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        MainMemoFilterHeader()
        MainMemoSortOptionsRow(
            selectedOption = filter.sortOption,
            selectedAscending = filter.sortAscending,
            onSortOptionSelected = onSortOptionSelected,
        )
        MainMemoDateRangeSection(
            filter = filter,
            hasDateFilter = hasDateFilter,
            onOpenStartDatePicker = onOpenStartDatePicker,
            onOpenEndDatePicker = onOpenEndDatePicker,
            onClearStartDate = onClearStartDate,
            onClearEndDate = onClearEndDate,
        )
    }
}

@Composable
internal fun MainMemoDatePickerHost(
    datePickerTarget: DatePickerTarget?,
    filter: MemoListFilter,
    onStartDateSelected: (LocalDate?) -> Unit,
    onEndDateSelected: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    when (datePickerTarget) {
        DatePickerTarget.START -> {
            MainMemoDatePickerDialog(
                title = stringResource(R.string.main_filter_pick_start_date),
                initialDate = filter.startDate,
                onConfirm = { date ->
                    onStartDateSelected(date)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        DatePickerTarget.END -> {
            MainMemoDatePickerDialog(
                title = stringResource(R.string.main_filter_pick_end_date),
                initialDate = filter.endDate,
                onConfirm = { date ->
                    onEndDateSelected(date)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        null -> Unit
    }
}

@Composable
internal fun MainMemoSortOptionsRow(
    selectedOption: MemoSortOption,
    selectedAscending: Boolean,
    onSortOptionSelected: (MemoSortOption) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        MemoSortOption.entries.forEach { option ->
            MainMemoSortButton(
                modifier = Modifier.weight(1f),
                text = sortOptionLabel(option),
                directionLabel = if (selectedOption == option) sortDirectionLabel(selectedAscending) else null,
                icon = sortOptionIcon(option),
                selected = selectedOption == option,
                benchmarkTag =
                    when (option) {
                        MemoSortOption.CREATED_TIME -> BenchmarkAnchorContract.SORT_OPTION_CREATED_TIME
                        MemoSortOption.UPDATED_TIME -> BenchmarkAnchorContract.SORT_OPTION_UPDATED_TIME
                    },
                benchmarkSelectedTag =
                    when (option) {
                        MemoSortOption.CREATED_TIME -> BenchmarkAnchorContract.SORT_SELECTED_CREATED_TIME
                        MemoSortOption.UPDATED_TIME -> BenchmarkAnchorContract.SORT_SELECTED_UPDATED_TIME
                    },
                onClick = { onSortOptionSelected(option) },
            )
        }
    }
}

@Composable
internal fun MainMemoDateRangeSection(
    filter: MemoListFilter,
    hasDateFilter: Boolean,
    onOpenStartDatePicker: () -> Unit,
    onOpenEndDatePicker: () -> Unit,
    onClearStartDate: () -> Unit,
    onClearEndDate: () -> Unit,
) {
    MainMemoFilterSectionCard(
        icon = Icons.Rounded.CalendarMonth,
        title = stringResource(R.string.main_filter_section_time_range),
        isActive = hasDateFilter,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            MainMemoDateField(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.main_filter_start_date),
                value = filter.startDate.formatOrDefault(),
                hasValue = filter.startDate != null,
                onPick = onOpenStartDatePicker,
                onClear = onClearStartDate,
            )
            MainMemoDateField(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.main_filter_end_date),
                value = filter.endDate.formatOrDefault(),
                hasValue = filter.endDate != null,
                onPick = onOpenEndDatePicker,
                onClear = onClearEndDate,
            )
        }
    }
}

@Composable
internal fun MainMemoDateFieldContent(
    label: String,
    value: String,
    hasValue: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
    ) {
        Icon(
            imageVector = Icons.Rounded.CalendarMonth,
            contentDescription = null,
            tint =
                if (hasValue) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun BoxScope.MainMemoDateFieldClearAction(onClear: () -> Unit) {
    IconButton(
        onClick = onClear,
        modifier =
            Modifier
                .size(24.dp)
                .align(Alignment.TopEnd),
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.action_clear),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sortOptionIcon(option: MemoSortOption): ImageVector =
    when (option) {
        MemoSortOption.CREATED_TIME -> Icons.Rounded.EditCalendar
        MemoSortOption.UPDATED_TIME -> Icons.Rounded.Update
    }

@Composable
private fun sortDirectionLabel(sortAscending: Boolean): String =
    if (sortAscending) {
        stringResource(R.string.main_filter_sort_direction_ascending)
    } else {
        stringResource(R.string.main_filter_sort_direction_descending)
    }
