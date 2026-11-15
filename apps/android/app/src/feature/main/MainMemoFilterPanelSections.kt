package com.lomo.app.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.ui.component.common.LomoButtonDefaults
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIconSpec
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
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
    onHasTodoChanged: (Boolean?) -> Unit,
    onHasAttachmentChanged: (Boolean?) -> Unit,
    onHasUrlChanged: (Boolean?) -> Unit,
    onSortDirectionSelected: (Boolean) -> Unit = {},
    onResetFilter: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
        MainMemoFilterHeader(
            isFilterActive = filter.isActive,
            onResetFilter = onResetFilter,
        )
        MainMemoSortOptionsRow(
            selectedOption = filter.sortOption,
            selectedAscending = filter.sortAscending,
            onSortOptionSelected = onSortOptionSelected,
            onSortDirectionSelected = onSortDirectionSelected,
        )
        MainMemoContentFlagsRow(
            filter = filter,
            onHasTodoChanged = onHasTodoChanged,
            onHasAttachmentChanged = onHasAttachmentChanged,
            onHasUrlChanged = onHasUrlChanged,
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
    onSortDirectionSelected: (Boolean) -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        MemoSortOption.entries.forEach { option ->
            val isSelected = selectedOption == option
            MainMemoSortButton(
                modifier = Modifier.weight(1f),
                text = sortOptionLabel(option),
                directionLabel = if (isSelected) sortDirectionLabel(selectedAscending) else null,
                icon = sortOptionIcon(option),
                selected = isSelected,
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
                onClick = {
                    if (isSelected) {
                        onSortDirectionSelected(!selectedAscending)
                    } else {
                        onSortOptionSelected(option)
                    }
                },
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
    MainMemoDateRangeSectionCard(
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
internal fun MainMemoDateFieldContent(label: String, value: String, hasValue: Boolean) {
    val colors = mainMemoDateFieldColors(hasValue = hasValue, colorScheme = MaterialTheme.colorScheme)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
    ) {
        LomoIcon(
            icon = LomoIcons.CalendarMonth,
            contentDescription = null,
            tint = colors.iconColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.labelColor,
            textAlign = TextAlign.Center,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.valueColor,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun BoxScope.MainMemoDateFieldClearAction(onClear: () -> Unit) {
    LomoIconButton(
        onClick = onClear,
        modifier =
        Modifier
            .size(24.dp)
            .align(Alignment.TopEnd),
    ) {
        val colors = mainMemoDateFieldColors(hasValue = true, colorScheme = MaterialTheme.colorScheme)
        LomoIcon(
            icon = LomoIcons.Close,
            contentDescription = stringResource(R.string.action_clear),
            modifier = Modifier.size(16.dp),
            tint = colors.clearActionColor,
        )
    }
}

private fun sortOptionIcon(option: MemoSortOption): LomoIconSpec = when (option) {
    MemoSortOption.CREATED_TIME -> LomoIcons.EditCalendar
    MemoSortOption.UPDATED_TIME -> LomoIcons.Update
}

@Composable
internal fun MainMemoContentFlagsRow(
    filter: MemoListFilter,
    onHasTodoChanged: (Boolean?) -> Unit,
    onHasAttachmentChanged: (Boolean?) -> Unit,
    onHasUrlChanged: (Boolean?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        ContentFlagTile(
            modifier = Modifier.weight(1f),
            icon = LomoIcons.CheckBox,
            label = stringResource(R.string.main_filter_content_todo),
            value = filter.hasTodo,
            onValueChanged = onHasTodoChanged,
        )
        ContentFlagTile(
            modifier = Modifier.weight(1f),
            icon = LomoIcons.InsertDriveFile,
            label = stringResource(R.string.main_filter_content_attachment),
            value = filter.hasAttachment,
            onValueChanged = onHasAttachmentChanged,
        )
        ContentFlagTile(
            modifier = Modifier.weight(1f),
            icon = LomoIcons.Link,
            label = stringResource(R.string.main_filter_content_url),
            value = filter.hasUrl,
            onValueChanged = onHasUrlChanged,
        )
    }
}

@Composable
private fun ContentFlagTile(
    icon: LomoIconSpec,
    label: String,
    value: Boolean?,
    onValueChanged: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalAppHapticFeedback.current
    val containerColor =
        when (value) {
            true -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            false -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        }
    val contentColor =
        when (value) {
            true -> MaterialTheme.colorScheme.onSecondaryContainer
            false -> MaterialTheme.colorScheme.onErrorContainer
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val stateLabelRes =
        when (value) {
            true -> R.string.main_filter_content_state_required
            false -> R.string.main_filter_content_state_excluded
            null -> null
        }

    LomoFilledTonalButton(
        onClick = {
            haptic.medium()
            onValueChanged(nextContentFlagState(value))
        },
        modifier = modifier,
        shapes = LomoButtonDefaults.tileShapes(),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).height(64.dp),
        ) {
            LomoIcon(
                icon = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (stateLabelRes != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(stateLabelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun nextContentFlagState(current: Boolean?): Boolean? = when (current) {
    null -> true
    true -> false
    false -> null
}

@Composable
private fun sortDirectionLabel(sortAscending: Boolean): String = if (sortAscending) {
    stringResource(R.string.main_filter_sort_direction_ascending)
} else {
    stringResource(R.string.main_filter_sort_direction_descending)
}
