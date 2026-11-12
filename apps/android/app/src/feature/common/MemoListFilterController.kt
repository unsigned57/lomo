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

    /**
     * Required single-choice sort option: selecting an option only changes `sortOption` and never
     * touches the direction axis — re-selecting the current option is a no-op, not a direction
     * toggle. Direction is owned by [onSortDirectionSelected].
     */
    val onSortOptionSelected: (MemoSortOption) -> Unit = { option ->
        _filter.value = _filter.value.copy(sortOption = option)
    }

    /** Dedicated sort-direction axis, driven by the filter sheet's direction toggle. */
    val onSortDirectionSelected: (Boolean) -> Unit = { ascending ->
        _filter.value = _filter.value.copy(sortAscending = ascending)
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

    /**
     * Restore a previously captured filter as part of session restore — the caller owns when the
     * whole session is republished, so restoring never produces an intermediate partial filter.
     */
    fun restore(filter: MemoListFilter) {
        _filter.value = filter
    }
}
