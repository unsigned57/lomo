package com.lomo.app.feature.main

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.app.feature.common.AppConfigUiCoordinator
import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.domain.usecase.ObserveSidebarStatisticsUseCase
import com.lomo.ui.component.navigation.SidebarStats
import com.lomo.ui.component.navigation.SidebarTag
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class SidebarViewModel(
    observeSidebarStatisticsUseCase: ObserveSidebarStatisticsUseCase,
    private val stateHolder: MainSidebarStateHolder,
    private val appConfigCoordinator: AppConfigUiCoordinator,
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : ViewModel() {
    @Immutable
    data class SidebarUiState(
        val stats: SidebarStats = SidebarStats(),
        val memoCountByDate: ImmutableMap<LocalDate, Int> = persistentMapOf(),
        val tags: ImmutableList<SidebarTag> = persistentListOf(),
        val rootTagOrder: ImmutableList<String> = persistentListOf(),
    )

    val searchQuery: StateFlow<String> = stateHolder.searchQuery

    val sidebarUiState: StateFlow<SidebarUiState> =
        combine(
            observeSidebarStatisticsUseCase(),
            appConfigCoordinator.sidebarTagOrder(),
        ) { statistics, tagOrder ->
            SidebarUiState(
                stats =
                SidebarStats(
                    memoCount = statistics.memoCount,
                    tagCount = statistics.tagCounts.size,
                    dayCount = statistics.memoCountByDate.size,
                ),
                memoCountByDate = statistics.memoCountByDate.toImmutableMap(),
                tags =
                statistics.tagCounts
                    .sortedWith(compareByDescending<MemoTagCount> { it.count }.thenBy { it.name })
                    .map { tagCount -> SidebarTag(name = tagCount.name, count = tagCount.count) }
                    .toImmutableList(),
                rootTagOrder = tagOrder.toImmutableList(),
            )
        }.flowOn(dispatcherProvider.default)
            .stateIn(viewModelScope, appWhileSubscribed(), SidebarUiState())

    fun onSearch(query: String) {
        stateHolder.updateSearchQuery(query)
    }

    fun updateTagOrder(order: List<String>) {
        viewModelScope.launch { appConfigCoordinator.updateSidebarTagOrder(order) }
    }

    internal val hostBindings: MainSidebarBindings =
        MainSidebarBindings(
            searchQuery = searchQuery,
            sidebarUiState = sidebarUiState,
            onUpdateTagOrder = ::updateTagOrder,
        )
}
