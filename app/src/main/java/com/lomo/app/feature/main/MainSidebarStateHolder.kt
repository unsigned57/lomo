package com.lomo.app.feature.main


import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


class MainSidebarStateHolder {
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

        fun updateSearchQuery(query: String) {
            _searchQuery.value = query
        }

        fun clearFilters() {
            _searchQuery.value = ""
        }
    }
