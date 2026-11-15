package com.lomo.app.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.app.feature.main.DatePickerTarget
import com.lomo.app.feature.main.MainMemoContentFlagsRow
import com.lomo.app.feature.main.MainMemoDatePickerHost
import com.lomo.app.feature.main.MainMemoDateRangeSection
import com.lomo.app.feature.main.MainMemoSortButton
import com.lomo.domain.model.DailyReviewOptions
import com.lomo.domain.model.DailyReviewSort
import com.lomo.domain.model.MemoListFilter
import com.lomo.ui.component.common.LomoModalBottomSheet
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.component.common.rememberModalSheetPresentation
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback

/**
 * M3 Expressive filter and sort sheet for Daily Review, aligned with [com.lomo.app.feature.main.MainMemoFilterSheet].
 * Mode and filter changes apply immediately on button interaction without a bottom confirmation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DailyReviewOptionsSheet(
    options: DailyReviewOptions,
    onOptionsChange: (DailyReviewOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalAppHapticFeedback.current
    var sort by remember(options.sort) { mutableStateOf(options.sort) }
    var filter by remember(options.filter) { mutableStateOf(options.filter) }
    var dateTarget by remember { mutableStateOf<DatePickerTarget?>(null) }
    val datesValid =
        filter.startDate == null ||
            filter.endDate == null ||
            checkNotNull(filter.startDate) <= checkNotNull(filter.endDate)
    val isModified = sort != DailyReviewSort.RECENT || filter.isActive
    val presentation = rememberModalSheetPresentation()

    fun commitChange(newSort: DailyReviewSort = sort, newFilter: MemoListFilter = filter) {
        sort = newSort
        filter = newFilter
        val valid =
            newFilter.startDate == null ||
                newFilter.endDate == null ||
                checkNotNull(newFilter.startDate) <= checkNotNull(newFilter.endDate)
        if (valid) {
            onOptionsChange(DailyReviewOptions(sort = newSort, filter = newFilter))
        }
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
                .padding(horizontal = AppSpacing.ScreenHorizontalPadding)
                .padding(bottom = AppSpacing.ExtraLarge)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Medium),
        ) {
            DailyReviewFilterHeader(
                isModified = isModified,
                onReset = {
                    commitChange(
                        newSort = DailyReviewSort.RECENT,
                        newFilter = MemoListFilter(),
                    )
                },
            )
            DailyReviewOrderChoices(
                sort = sort,
                onSelect = { selectedSort ->
                    if (selectedSort != sort) {
                        commitChange(newSort = selectedSort)
                    }
                },
            )
            MainMemoContentFlagsRow(
                filter = filter,
                onHasTodoChanged = { commitChange(newFilter = filter.copy(hasTodo = it)) },
                onHasAttachmentChanged = { commitChange(newFilter = filter.copy(hasAttachment = it)) },
                onHasUrlChanged = { commitChange(newFilter = filter.copy(hasUrl = it)) },
            )
            MainMemoDateRangeSection(
                filter = filter,
                hasDateFilter = filter.hasDateRange,
                onOpenStartDatePicker = {
                    haptic.medium()
                    dateTarget = DatePickerTarget.START
                },
                onOpenEndDatePicker = {
                    haptic.medium()
                    dateTarget = DatePickerTarget.END
                },
                onClearStartDate = {
                    haptic.medium()
                    commitChange(newFilter = filter.copy(startDate = null))
                },
                onClearEndDate = {
                    haptic.medium()
                    commitChange(newFilter = filter.copy(endDate = null))
                },
            )
            if (!datesValid) {
                Surface(
                    shape = AppShapes.Medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.review_invalid_dates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                    )
                }
            }
        }
    }
    MainMemoDatePickerHost(
        datePickerTarget = dateTarget,
        filter = filter,
        onStartDateSelected = { commitChange(newFilter = filter.copy(startDate = it)) },
        onEndDateSelected = { commitChange(newFilter = filter.copy(endDate = it)) },
        onDismiss = { dateTarget = null },
    )
}

@Composable
private fun DailyReviewFilterHeader(isModified: Boolean, onReset: () -> Unit) {
    val haptic = LocalAppHapticFeedback.current
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
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
            text = stringResource(R.string.review_options_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        LomoTextButton(
            onClick = {
                haptic.medium()
                onReset()
            },
            colors =
            ButtonDefaults.textButtonColors(
                contentColor =
                if (isModified) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        ) {
            Text(
                text = stringResource(R.string.action_reset),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun DailyReviewOrderChoices(sort: DailyReviewSort, onSelect: (DailyReviewSort) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
    ) {
        DailyReviewSort.entries.forEach { option ->
            val selected = option == sort
            val label =
                when (option) {
                    DailyReviewSort.RECENT -> R.string.review_sort_recent
                    DailyReviewSort.PAST -> R.string.review_sort_past
                    DailyReviewSort.RANDOM -> R.string.review_sort_random
                }
            val icon =
                when (option) {
                    DailyReviewSort.RECENT -> LomoIcons.Update
                    DailyReviewSort.PAST -> LomoIcons.History
                    DailyReviewSort.RANDOM -> LomoIcons.Shuffle
                }
            MainMemoSortButton(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                text = stringResource(label),
                directionLabel = null,
                icon = icon,
                selected = selected,
            )
        }
    }
}
