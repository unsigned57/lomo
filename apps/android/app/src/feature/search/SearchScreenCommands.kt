package com.lomo.app.feature.search

import androidx.compose.runtime.Stable
import com.lomo.app.feature.common.MemoListFilterController
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.preferences.AppPreferencesState
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSearchMode
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.component.common.ExitAnimationRegistry
import kotlinx.coroutines.flow.StateFlow

/** Search query and filter state flows consumed at the search screen root. */
@Stable
internal data class SearchQueryFlows(
    val searchQuery: StateFlow<String>,
    val searchMode: StateFlow<MemoSearchMode>,
    val searchFilter: StateFlow<MemoListFilter>,
    val isSearching: StateFlow<Boolean>,
)

/** Search preferences, workspace paths, image mappings, and error state flows. */
@Stable
internal data class SearchMediaFlows(
    val appPreferences: StateFlow<AppPreferencesState>,
    val rootDirectory: StateFlow<String?>,
    val imageDirectory: StateFlow<String?>,
    val imageMap: StateFlow<Map<String, android.net.Uri>>,
    val errorMessage: StateFlow<String?>,
)

/** Hoisted snapshot state-flow bindings exposed by [SearchViewModel]. */
@Stable
internal data class SearchUiSnapshotBindings(val query: SearchQueryFlows, val media: SearchMediaFlows)

/**
 * The search ViewModel's commands, hoisted once at the screen root. Composables below receive
 * these explicit capabilities instead of the ViewModel itself.
 */
@Stable
internal class SearchScreenCommands(
    val onQueryChanged: (String) -> Unit,
    val onToggleSearchMode: () -> Unit,
    val onTodoClick: (Memo, MarkdownSourceSpan) -> Unit,
    val onEditorOpenFailure: (Throwable) -> Unit,
    val exitAnimationRegistry: ExitAnimationRegistry<MemoUiModel>,
    val filterController: MemoListFilterController,
)

/** Builds the hoisted snapshot flow bundle for [SearchScreen]. */
internal fun SearchViewModel.buildSnapshotBindings(): SearchUiSnapshotBindings = SearchUiSnapshotBindings(
    query =
    SearchQueryFlows(
        searchQuery = searchQuery,
        searchMode = searchMode,
        searchFilter = searchFilter,
        isSearching = isSearching,
    ),
    media =
    SearchMediaFlows(
        appPreferences = appPreferences,
        rootDirectory = rootDirectory,
        imageDirectory = imageDirectory,
        imageMap = imageMap,
        errorMessage = errorMessage,
    ),
)
