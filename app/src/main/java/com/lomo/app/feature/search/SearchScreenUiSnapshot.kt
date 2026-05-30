package com.lomo.app.feature.search

import com.lomo.domain.model.MemoListFilter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet

internal data class SearchScreenUiSnapshot(
    val query: String,
    val showLoading: Boolean,
    val searchResults: ImmutableList<com.lomo.app.feature.main.MemoUiModel>,
    val canLoadMore: Boolean,
    val searchFilter: MemoListFilter,
    val dateFormat: String,
    val timeFormat: String,
    val shareCardShowTime: Boolean,
    val shareCardShowSignature: Boolean,
    val shareCardSignatureText: String,
    val customFontPath: String?,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrderForSearch: ImmutableList<String>,
    val inputToolbarToolOrder: ImmutableList<String>,
    val rootDirectory: String?,
    val imageDirectory: String?,
    val imageMap: ImmutableMap<String, android.net.Uri>,
    val deletingMemoIds: ImmutableSet<String>,
    val errorMessage: String?,
)
