package com.lomo.app.feature.main

import com.lomo.domain.model.MemoListFilter

internal enum class MainListPresentationMode {
    PagedDefault,
    FilteredList,
}

internal fun resolveMainListPresentationMode(
    query: String,
    filter: MemoListFilter,
): MainListPresentationMode =
    if (query.isBlank() && !filter.isActive) {
        MainListPresentationMode.PagedDefault
    } else {
        MainListPresentationMode.FilteredList
    }
