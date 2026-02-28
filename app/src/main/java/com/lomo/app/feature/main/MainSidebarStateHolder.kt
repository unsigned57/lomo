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

        private val _selectedTag = MutableStateFlow<String?>(null)
        val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

        fun updateSearchQuery(query: String) {
            _searchQuery.value = query
        }

        fun updateSelectedTag(tag: String?) {
            _selectedTag.value = if (_selectedTag.value == tag) null else tag
        }

        fun clearFilters() {
            _searchQuery.value = ""
            _selectedTag.value = null
        }
    }
