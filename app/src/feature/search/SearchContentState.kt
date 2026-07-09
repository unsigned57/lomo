package com.lomo.app.feature.search

internal enum class SearchContentState(val key: String) {
    EmptyInitial("empty-initial"),
    Loading("loading"),
    NoResults("no-results"),
    Results("results"),
}

internal fun resolveSearchContentState(
    query: String,
    showLoading: Boolean,
    hasResults: Boolean,
    hasActiveExits: Boolean = false,
): SearchContentState =
    when {
        query.isEmpty() -> SearchContentState.EmptyInitial
        showLoading -> SearchContentState.Loading
        !hasResults && !hasActiveExits -> SearchContentState.NoResults
        else -> SearchContentState.Results
    }



