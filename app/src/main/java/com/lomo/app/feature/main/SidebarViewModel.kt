package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.util.StorageFilenameFormats
import com.lomo.ui.component.navigation.SidebarStats
import com.lomo.ui.component.navigation.SidebarTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SidebarViewModel
    @Inject
    constructor(
        private val repository: MemoRepository,
        private val stateHolder: MainSidebarStateHolder,
    ) : ViewModel() {
        data class SidebarUiState(
            val stats: SidebarStats = SidebarStats(),
            val memoCountByDate: Map<LocalDate, Int> = emptyMap(),
            val tags: List<SidebarTag> = emptyList(),
        )

        val searchQuery: StateFlow<String> = stateHolder.searchQuery
        val selectedTag: StateFlow<String?> = stateHolder.selectedTag

        val sidebarUiState: StateFlow<SidebarUiState> =
            combine(
                repository.getMemoCountFlow(),
                repository.getMemoCountByDateFlow(),
                repository.getTagCountsFlow(),
            ) { memoCount, memoCountByDateRaw, tagCounts ->
                val memoCountByDate =
                    memoCountByDateRaw
                        .asSequence()
                        .mapNotNull { (dateStr, count) ->
                            StorageFilenameFormats.parseOrNull(dateStr)?.let { parsed -> parsed to count }
                        }.toMap()

                SidebarUiState(
                    stats =
                        SidebarStats(
                            memoCount = memoCount,
                            tagCount = tagCounts.size,
                            dayCount = memoCountByDate.size,
                        ),
                    memoCountByDate = memoCountByDate,
                    tags =
                        tagCounts
                            .sortedWith(compareByDescending<MemoTagCount> { it.count }.thenBy { it.name })
                            .map { tagCount -> SidebarTag(name = tagCount.name, count = tagCount.count) },
                )
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SidebarUiState())

        fun onSearch(query: String) {
            stateHolder.updateSearchQuery(query)
        }

        fun onTagSelected(tag: String?) {
            stateHolder.updateSelectedTag(tag)
        }

        fun clearFilters() {
            stateHolder.clearFilters()
        }
    }
