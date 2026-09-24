package com.lomo.app.feature.main

import com.lomo.app.feature.common.MemoListFilterController
import com.lomo.domain.model.MemoListFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single owner of the main screen's list session: the search query and the structural filter move
 * together so a screen session never observes half-restored or half-cleared state.
 */
class MainSidebarStateHolder {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filterController = MemoListFilterController()
    val memoListFilter: StateFlow<MemoListFilter> = filterController.filter

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * Restore a saved session atomically: the query and filter land before the viewport anchor is
     * applied, so the list never composes a stale query under a restored position.
     */
    fun restoreSession(
        query: String,
        filter: MemoListFilter,
    ) {
        _searchQuery.value = query
        filterController.restore(filter)
    }

    /**
     * Reset the whole session back to the unfiltered default view — used when a focus request asks
     * for the default list rather than a filtered session.
     */
    fun clearAll() {
        _searchQuery.value = ""
        filterController.clear()
    }
}
