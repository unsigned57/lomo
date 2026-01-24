package com.lomo.app.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lomo.domain.usecase.GetMemoStatsUseCase
import com.lomo.ui.component.navigation.SidebarStats
import com.lomo.ui.component.navigation.SidebarTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * ViewModel for managing the Sidebar navigation and statistics state.
 *
 * Responsibilities:
 * - Transforming [MemoStats] into [SidebarUiState] for UI consumption.
 * - Handling date conversion (EpochMilli -> LocalDate) for CalendarHeatmap.
 * - Sorting and aggregating tags for display.
 *
 * This refactor decouples statistical logic from the main content flow.
 */
@HiltViewModel
class SidebarViewModel
    @Inject
    constructor(
        getMemoStatsUseCase: GetMemoStatsUseCase,
    ) : ViewModel() {
        /**
         * UI State for the Sidebar Drawer.
         *
         * @property stats General counts (total memos, tags, active days).
         * @property memoCountByDate Map of Date to count for Heatmap visualization.
         * @property tags List of available tags with their usage counts.
         */
        data class SidebarUiState(
            val stats: SidebarStats = SidebarStats(0, 0, 0),
            val memoCountByDate: Map<LocalDate, Int> = emptyMap(),
            val tags: List<SidebarTag> = emptyList(),
        )

        val sidebarUiState: StateFlow<SidebarUiState> =
            getMemoStatsUseCase()
                .map { stats ->
                    val localDates =
                        stats.timestamps.map {
                            Instant
                                .ofEpochMilli(it)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }

                    val uiStats =
                        SidebarStats(
                            memoCount = stats.totalMemos,
                            tagCount = stats.tagCounts.size,
                            dayCount = localDates.distinct().size,
                        )

                    val memoCountByDate = localDates.groupBy { it }.mapValues { it.value.size }

                    val tags =
                        stats.tagCounts
                            .map { tagCount ->
                                SidebarTag(
                                    tagCount.name,
                                    tagCount.count,
                                )
                            }.sortedByDescending { tagCount -> tagCount.count }

                    SidebarUiState(uiStats, memoCountByDate, tags)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = SidebarUiState(),
                )
    }
