package com.lomo.app.feature.main

import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@ActivityRetainedScoped
class MainSidebarStateHolder
    @Inject
    constructor() {
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

        fun updateSearchQuery(query: String) {
            _searchQuery.value = query
        }

        fun clearFilters() {
            _searchQuery.value = ""
        }
    }
