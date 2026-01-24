package com.lomo.app.feature.main

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Sealed interface representing the different states of the main UI. Using sealed interface for
 * type-safe state handling and exhaustive when expressions.
 */
sealed interface MainUiState {
    /** Initial loading state when the app first starts. */
    data object Loading : MainUiState

    /** Error state with an error message. */
    data class Error(
        val message: String,
    ) : MainUiState

    /** Success state containing all UI data. */
    data class Success(
        val searchQuery: String = "",
        val selectedTag: String? = null,
        val rootDirectory: String? = null,
        val hasDirectory: Boolean = false,
        val sidebarState: SidebarUiState = SidebarUiState(),
    ) : MainUiState

    companion object {
        val Initial: MainUiState = Loading
    }
}

/** Sidebar-specific UI state. */
data class SidebarUiState(
    val memoCount: Int = 0,
    val tagCount: Int = 0,
    val dayCount: Int = 0,
    val memoCountByDate: Map<java.time.LocalDate, Int> = emptyMap(),
    val tags: ImmutableList<SidebarTagState> = persistentListOf(),
)

/** Immutable tag state for the sidebar. */
data class SidebarTagState(
    val name: String,
    val count: Int,
)

/** Sealed interface for memo filter states. */
sealed interface MemoFilter {
    data object All : MemoFilter

    data class ByTag(
        val tag: String,
    ) : MemoFilter

    data class ByQuery(
        val query: String,
    ) : MemoFilter

    data class Combined(
        val query: String,
        val tag: String,
    ) : MemoFilter

    companion object {
        fun from(
            query: String,
            tag: String?,
        ): MemoFilter =
            when {
                query.isNotBlank() && tag != null -> Combined(query, tag)
                query.isNotBlank() -> ByQuery(query)
                tag != null -> ByTag(tag)
                else -> All
            }
    }
}
