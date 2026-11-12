package com.lomo.app.feature.search

import com.lomo.app.feature.common.MemoListFilterController
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.ui.component.common.ExitAnimationRegistry

/**
 * The search ViewModel's commands, hoisted once at the screen root. Composables below receive
 * these explicit capabilities instead of the ViewModel itself.
 */
internal class SearchScreenCommands(
    val onQueryChanged: (String) -> Unit,
    val onToggleSearchMode: () -> Unit,
    val onTodoClick: (Memo, MarkdownSourceSpan) -> Unit,
    val onEditorOpenFailure: (Throwable) -> Unit,
    val exitAnimationRegistry: ExitAnimationRegistry<MemoUiModel>,
    val filterController: MemoListFilterController,
)

internal fun SearchViewModel.screenCommands(): SearchScreenCommands = SearchScreenCommands(
    onQueryChanged = ::onSearchQueryChanged,
    onToggleSearchMode = ::onToggleSearchMode,
    onTodoClick = ::toggleTodo,
    onEditorOpenFailure = ::reportError,
    exitAnimationRegistry = exitAnimationRegistry,
    filterController = searchFilterController,
)
