package com.lomo.app.feature.common

import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSortOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Reusable memo filter state container. A single [MemoListFilterController] owns one
 * [MemoListFilter] StateFlow plus every mutator the filter sheet needs. ViewModels create
 * their own instance and screens consume it via `MemoFilterSheetHost`.
 */
class MemoListFilterController {
    private val _filter = MutableStateFlow(MemoListFilter())
    val filter: StateFlow<MemoListFilter> = _filter.asStateFlow()

    val onSortOptionSelected: (MemoSortOption) -> Unit = { option ->
        val current = _filter.value
        _filter.value =
            if (current.sortOption == option) {
                current.copy(sortAscending = !current.sortAscending)
            } else {
                current.copy(sortOption = option, sortAscending = true)
            }
    }

    val onStartDateSelected: (LocalDate?) -> Unit = { date ->
        val current = _filter.value
        val adjustedEnd =
            current.endDate?.takeUnless { endDate ->
                date != null && endDate.isBefore(date)
            }
        _filter.value = current.copy(startDate = date, endDate = adjustedEnd)
    }

    val onEndDateSelected: (LocalDate?) -> Unit = { date ->
        val current = _filter.value
        val adjustedStart =
            current.startDate?.takeUnless { startDate ->
                date != null && startDate.isAfter(date)
            }
        _filter.value = current.copy(startDate = adjustedStart, endDate = date)
    }

    val onHasTodoChanged: (Boolean?) -> Unit = { value ->
        _filter.value = _filter.value.copy(hasTodo = value)
    }

    val onHasAttachmentChanged: (Boolean?) -> Unit = { value ->
        _filter.value = _filter.value.copy(hasAttachment = value)
    }

    val onHasUrlChanged: (Boolean?) -> Unit = { value ->
        _filter.value = _filter.value.copy(hasUrl = value)
    }

    val filterByDate: (LocalDate) -> Unit = { date ->
        _filter.value = _filter.value.copy(startDate = date, endDate = date)
    }

    val clearFilter: () -> Unit = {
        _filter.value = _filter.value.copy(
            startDate = null,
            endDate = null,
            hasTodo = null,
            hasAttachment = null,
            hasUrl = null,
        )
    }

    val clearDateRange: () -> Unit = {
        _filter.value = _filter.value.copy(startDate = null, endDate = null)
    }

    val clear: () -> Unit = {
        _filter.value = MemoListFilter()
    }
}
