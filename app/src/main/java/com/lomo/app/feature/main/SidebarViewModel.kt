package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.MemoUiCoordinator
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.ui.component.navigation.SidebarStats
import com.lomo.ui.component.navigation.SidebarTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SidebarViewModel
    @Inject
    constructor(
        private val memoUiCoordinator: MemoUiCoordinator,
        private val stateHolder: MainSidebarStateHolder,
        private val appConfigCoordinator: AppConfigUiCoordinator,
    ) : ViewModel() {
        data class SidebarUiState(
            val stats: SidebarStats = SidebarStats(),
            val memoCountByDate: Map<LocalDate, Int> = emptyMap(),
            val tags: List<SidebarTag> = emptyList(),
            val rootTagOrder: List<String> = emptyList(),
        )

        val searchQuery: StateFlow<String> = stateHolder.searchQuery

        val sidebarUiState: StateFlow<SidebarUiState> =
            combine(
                memoUiCoordinator.memoCount(),
                memoUiCoordinator.memoCountByDate(),
                memoUiCoordinator.tagCounts(),
                appConfigCoordinator.sidebarTagOrder(),
            ) { memoCount, memoCountByDateRaw, tagCounts, tagOrder ->
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
                    rootTagOrder = tagOrder,
                )
            }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, appWhileSubscribed(), SidebarUiState())

        fun onSearch(query: String) {
            stateHolder.updateSearchQuery(query)
        }

        fun clearFilters() {
            stateHolder.updateSearchQuery("")
        }

        fun updateTagOrder(order: List<String>) {
            viewModelScope.launch { appConfigCoordinator.updateSidebarTagOrder(order) }
        }
    }
