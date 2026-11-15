package com.lomo.app.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.LomoButtonDefaults
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.component.common.LomoModalBottomSheet
import com.lomo.ui.component.common.LomoSheetTokens
import com.lomo.ui.component.common.rememberModalSheetPresentation
import com.lomo.ui.component.picker.ExpressiveDatePickerSurface
import com.lomo.ui.component.picker.ExpressivePickerDialog
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIconSpec
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
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
    onHasTodoChanged: (Boolean?) -> Unit,
    onHasAttachmentChanged: (Boolean?) -> Unit,
    onHasUrlChanged: (Boolean?) -> Unit,
    onDismiss: () -> Unit,
    onSortDirectionSelected: (Boolean) -> Unit = {},
    onResetFilter: () -> Unit = {},
) {
    val presentation = rememberModalSheetPresentation()
    val haptic = LocalAppHapticFeedback.current
    var datePickerTarget by rememberSaveable { mutableStateOf<DatePickerTarget?>(null) }
    val hasDateFilter = filter.startDate != null || filter.endDate != null
    val openStartDatePicker = {
        haptic.medium()
        datePickerTarget = DatePickerTarget.START
    }
    val openEndDatePicker = {
        haptic.medium()
        datePickerTarget = DatePickerTarget.END
    }
    val clearStartDate = {
        haptic.medium()
        onStartDateSelected(null)
    }
    val clearEndDate = {
        haptic.medium()
        onEndDateSelected(null)
    }

    LomoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = presentation.sheetState,
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged(presentation::onContentMeasured)
                .benchmarkAnchorRoot(BenchmarkAnchorContract.FILTER_SHEET_ROOT)
                .padding(horizontal = AppSpacing.ScreenHorizontalPadding)
                .padding(bottom = AppSpacing.ExtraLarge)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            MainMemoFilterSheetContent(
                filter = filter,
                hasDateFilter = hasDateFilter,
                onSortOptionSelected = onSortOptionSelected,
                onOpenStartDatePicker = openStartDatePicker,
                onOpenEndDatePicker = openEndDatePicker,
                onClearStartDate = clearStartDate,
                onClearEndDate = clearEndDate,
                onHasTodoChanged = onHasTodoChanged,
                onHasAttachmentChanged = onHasAttachmentChanged,
                onHasUrlChanged = onHasUrlChanged,
                onSortDirectionSelected = onSortDirectionSelected,
                onResetFilter = onResetFilter,
            )
        }
    }

    MainMemoDatePickerHost(
        datePickerTarget = datePickerTarget,
        filter = filter,
        onStartDateSelected = onStartDateSelected,
        onEndDateSelected = onEndDateSelected,
        onDismiss = { datePickerTarget = null },
    )
}

@Composable
internal fun MainMemoFilterHeader(isFilterActive: Boolean = false, onResetFilter: () -> Unit = {}) {
    val haptic = LocalAppHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = AppShapes.Medium,
        ) {
            LomoIcon(
                icon = LomoIcons.FilterAlt,
                contentDescription = null,
                modifier = Modifier.padding(AppSpacing.Small),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.main_filter_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (isFilterActive) {
            com.lomo.ui.component.common.LomoTextButton(
                onClick = {
                    haptic.medium()
                    onResetFilter()
                },
            ) {
                Text(stringResource(R.string.action_clear))
            }
        }
    }
}

@Composable
internal fun MainMemoDateRangeSectionCard(
    title: String,
    isActive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val iconColors = mainMemoDateRangeIconColors(isActive = isActive, colorScheme = MaterialTheme.colorScheme)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small),
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = iconColors.containerColor,
                    shape = AppShapes.Small,
                ) {
                    LomoIcon(
                        icon = LomoIcons.CalendarMonth,
                        contentDescription = null,
                        tint = iconColors.iconColor,
                        modifier = Modifier.padding(AppSpacing.Small),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.ExtraSmall))
            content()
        },
    )
}

@Composable
internal fun MainMemoSortButton(
    text: String,
    directionLabel: String?,
    icon: LomoIconSpec,
    selected: Boolean,
    modifier: Modifier = Modifier,
    benchmarkTag: String? = null,
    benchmarkSelectedTag: String? = null,
    onClick: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val containerColor by androidx.compose.animation.animateColorAsState(
        targetValue =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            LomoSheetTokens.tileSurfaceColor(MaterialTheme.colorScheme)
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "MemoSortContainer",
    )
    val contentColor by androidx.compose.animation.animateColorAsState(
        targetValue =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "MemoSortContent",
    )

    LomoFilledTonalButton(
        onClick = {
            haptic.medium()
            onClick()
        },
        modifier =
        modifier
            .benchmarkAnchor(
                if (selected) {
                    benchmarkSelectedTag ?: benchmarkTag
                } else {
                    benchmarkTag
                },
            ),
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
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            com.lomo.ui.component.common.LomoStateContent(
                state = directionLabel,
                contentKey = { it ?: "unselected" },
            ) { label ->
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        minLines = 1,
                        color = contentColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun MainMemoDateField(
    label: String,
    value: String,
    hasValue: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    onClear: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    val colors = mainMemoDateFieldColors(hasValue = hasValue, colorScheme = MaterialTheme.colorScheme)
    LomoFilledTonalButton(
        onClick = {
            haptic.medium()
            onPick()
        },
        modifier = modifier,
        shapes = LomoButtonDefaults.cardFieldShapes(),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.containerColor),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Small),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.ExtraSmall),
            ) {
                MainMemoDateFieldContent(label = label, value = value, hasValue = hasValue)
            }

            if (hasValue) {
                MainMemoDateFieldClearAction(onClear = onClear)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainMemoDatePickerDialog(
    title: String,
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
    val canClear = initialDate != null || selectedDate != null
    ExpressivePickerDialog(
        title = title,
        confirmLabel = stringResource(R.string.main_filter_date_dialog_action_apply),
        confirmEnabled = selectedDate != null,
        onConfirm = {
            if (selectedDate == null) {
                haptic.error()
            } else {
                haptic.medium()
                onConfirm(selectedDate)
            }
        },
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = {
            haptic.medium()
            onDismiss()
        },
        neutralLabel = if (canClear) stringResource(R.string.action_clear) else null,
        onNeutral = if (canClear) {
            {
                haptic.medium()
                onConfirm(null)
            }
        } else {
            null
        },
    ) {
        ExpressiveDatePickerSurface(state = datePickerState)
    }
}

@Composable
internal fun sortOptionLabel(option: MemoSortOption): String = when (option) {
    MemoSortOption.CREATED_TIME -> stringResource(R.string.main_filter_sort_created_time)
    MemoSortOption.UPDATED_TIME -> stringResource(R.string.main_filter_sort_updated_time)
}

@Composable
internal fun LocalDate?.formatOrDefault(): String =
    this?.format(DATE_LABEL_FORMATTER) ?: stringResource(R.string.main_filter_date_not_set)

private fun LocalDate.toEpochMillis(): Long = atStartOfDay(DEFAULT_ZONE)
    .toInstant()
    .toEpochMilli()

private fun Long.toLocalDate(): LocalDate = Instant
    .ofEpochMilli(this)
    .atZone(DEFAULT_ZONE)
    .toLocalDate()

internal enum class DatePickerTarget {
    START,
    END,
}

private val DATE_LABEL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val DEFAULT_ZONE: ZoneId = ZoneId.systemDefault()
