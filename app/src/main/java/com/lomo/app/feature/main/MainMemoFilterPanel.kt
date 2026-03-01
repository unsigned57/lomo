package com.lomo.app.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainMemoFilterSheet(
    filter: MemoListFilter,
    onSortOptionSelected: (MemoSortOption) -> Unit,
    onStartDateSelected: (LocalDate?) -> Unit,
    onEndDateSelected: (LocalDate?) -> Unit,
    onClearDateRange: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalAppHapticFeedback.current
    var datePickerTarget by rememberSaveable { mutableStateOf<DatePickerTarget?>(null) }
    val defaultSortOption = MemoListFilter().sortOption
    val activeFilterCount =
        buildList {
            if (filter.sortOption != defaultSortOption) add(Unit)
            if (filter.startDate != null) add(Unit)
            if (filter.endDate != null) add(Unit)
        }.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = AppSpacing.ExtraSmall,
        dragHandle = { MainMemoFilterDragHandle() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.ScreenHorizontalPadding)
                    .padding(bottom = AppSpacing.ExtraLarge)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            MainMemoFilterHeader(activeFilterCount = activeFilterCount)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                MemoSortOption.entries.forEach { option ->
                    val icon = when (option) {
                        MemoSortOption.CREATED_TIME -> Icons.Rounded.EditCalendar
                        MemoSortOption.UPDATED_TIME -> Icons.Rounded.Update
                    }
                    MainMemoSortButton(
                        modifier = Modifier.weight(1f),
                        text = sortOptionLabel(option),
                        icon = icon,
                        selected = filter.sortOption == option,
                        onClick = { onSortOptionSelected(option) }
                    )
                }
            }

            MainMemoFilterSectionCard(
                icon = Icons.Rounded.CalendarMonth,
                title = stringResource(R.string.main_filter_section_time_range),
                subtitle = stringResource(R.string.main_filter_date_section_hint),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    MainMemoDateField(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.main_filter_start_date),
                        value = filter.startDate.formatOrDefault(),
                        hasValue = filter.startDate != null,
                        onPick = {
                            haptic.medium()
                            datePickerTarget = DatePickerTarget.START
                        },
                        onClear = {
                            haptic.medium()
                            onStartDateSelected(null)
                        },
                    )
                    MainMemoDateField(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.main_filter_end_date),
                        value = filter.endDate.formatOrDefault(),
                        hasValue = filter.endDate != null,
                        onPick = {
                            haptic.medium()
                            datePickerTarget = DatePickerTarget.END
                        },
                        onClear = {
                            haptic.medium()
                            onEndDateSelected(null)
                        },
                    )
                }

                if (filter.startDate != null || filter.endDate != null) {
                    Spacer(modifier = Modifier.height(AppSpacing.Small))
                    MainMemoDateRangePreview(
                        startDate = filter.startDate,
                        endDate = filter.endDate,
                    )
                }
            }


        }
    }

    when (datePickerTarget) {
        DatePickerTarget.START -> {
            MainMemoDatePickerDialog(
                title = stringResource(R.string.main_filter_pick_start_date),
                fieldLabel = stringResource(R.string.main_filter_start_date),
                initialDate = filter.startDate,
                onConfirm = { date ->
                    onStartDateSelected(date)
                    datePickerTarget = null
                },
                onDismiss = { datePickerTarget = null },
            )
        }

        DatePickerTarget.END -> {
            MainMemoDatePickerDialog(
                title = stringResource(R.string.main_filter_pick_end_date),
                fieldLabel = stringResource(R.string.main_filter_end_date),
                initialDate = filter.endDate,
                onConfirm = { date ->
                    onEndDateSelected(date)
                    datePickerTarget = null
                },
                onDismiss = { datePickerTarget = null },
            )
        }

        null -> Unit
    }
}

@Composable
private fun MainMemoFilterHeader(activeFilterCount: Int) {
    val summaryText =
        if (activeFilterCount == 0) {
            stringResource(R.string.main_filter_summary_none)
        } else {
            stringResource(R.string.main_filter_summary_active, activeFilterCount)
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(AppSpacing.MediumSmall),
        ) {
            Icon(
                imageVector = Icons.Rounded.FilterAlt,
                contentDescription = null,
                modifier = Modifier.padding(AppSpacing.Small),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
            Text(
                text = stringResource(R.string.main_filter_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MainMemoFilterSectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(AppSpacing.Small),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AppSpacing.Small),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.ExtraSmall))
            content()
        },
    )
}

@Composable
private fun MainMemoSortButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        }
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptic.medium()
                onClick()
            },
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(12.dp).height(56.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun MainMemoDateField(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    hasValue: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    Surface(
        modifier =
            modifier
                .clip(RoundedCornerShape(AppSpacing.Small))
                .clickable {
                    haptic.medium()
                    onPick()
                },
        shape = RoundedCornerShape(AppSpacing.Small),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        ) {
            Icon(
                imageVector = Icons.Rounded.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (hasValue) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_clear),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MainMemoDateRangePreview(
    startDate: LocalDate?,
    endDate: LocalDate?,
) {
    val hasConflict = startDate != null && endDate != null && endDate.isBefore(startDate)
    val previewText =
        when {
            startDate != null && endDate != null ->
                stringResource(
                    R.string.main_filter_date_range_between,
                    startDate.format(DATE_LABEL_FORMATTER),
                    endDate.format(DATE_LABEL_FORMATTER),
                )

            startDate != null ->
                stringResource(
                    R.string.main_filter_date_range_from,
                    startDate.format(DATE_LABEL_FORMATTER),
                )

            endDate != null ->
                stringResource(
                    R.string.main_filter_date_range_until,
                    endDate.format(DATE_LABEL_FORMATTER),
                )

            else -> return
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (hasConflict) Icons.Rounded.Close else Icons.Rounded.CalendarMonth,
            contentDescription = null,
            tint = if (hasConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(AppSpacing.Small))
        Text(
            text = if (hasConflict) stringResource(R.string.main_filter_date_range_warning) else previewText,
            style = MaterialTheme.typography.bodySmall,
            color = if (hasConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainMemoDatePickerDialog(
    title: String,
    fieldLabel: String,
    initialDate: LocalDate?,
    onConfirm: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = initialDate?.toEpochMillis(),
        )
    val selectedDate = datePickerState.selectedDateMillis?.toLocalDate()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    if (selectedDate == null) {
                        haptic.error()
                    } else {
                        haptic.medium()
                        onConfirm(selectedDate)
                    }
                },
                enabled = selectedDate != null,
            ) {
                Text(text = stringResource(R.string.main_filter_date_dialog_action_apply))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall)) {
                if (initialDate != null || selectedDate != null) {
                    TextButton(
                        onClick = {
                            haptic.medium()
                            onConfirm(null)
                        },
                    ) {
                        Text(text = stringResource(R.string.action_clear))
                    }
                }
                TextButton(
                    onClick = {
                        haptic.medium()
                        onDismiss()
                    },
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        },
    ) {
        DatePicker(
            state = datePickerState,
            showModeToggle = false,
            title = {
                Text(
                    text = title,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                )
            }
        )
    }
}

@Composable
private fun MainMemoFilterDragHandle() {
    Box(
        modifier =
            Modifier
                .padding(vertical = AppSpacing.Large)
                .width(AppSpacing.ExtraLarge)
                .size(width = AppSpacing.ExtraLarge, height = AppSpacing.ExtraSmall)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
    )
}

@Composable
private fun sortOptionLabel(option: MemoSortOption): String =
    when (option) {
        MemoSortOption.CREATED_TIME -> stringResource(R.string.main_filter_sort_created_time)
        MemoSortOption.UPDATED_TIME -> stringResource(R.string.main_filter_sort_updated_time)
    }

@Composable
private fun LocalDate?.formatOrDefault(): String =
    this?.format(DATE_LABEL_FORMATTER) ?: stringResource(R.string.main_filter_date_not_set)

private fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(DEFAULT_ZONE)
        .toInstant()
        .toEpochMilli()

private fun Long.toLocalDate(): LocalDate =
    Instant
        .ofEpochMilli(this)
        .atZone(DEFAULT_ZONE)
        .toLocalDate()

private enum class DatePickerTarget {
    START,
    END,
}

private val DATE_LABEL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val DEFAULT_ZONE: ZoneId = ZoneId.systemDefault()
