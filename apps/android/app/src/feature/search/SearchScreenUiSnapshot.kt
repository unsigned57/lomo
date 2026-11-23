package com.lomo.app.feature.search

import com.lomo.domain.model.MemoListFilter
import com.lomo.domain.model.MemoSearchMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap

internal data class SearchScreenUiSnapshot(
    val query: String,
    val searchMode: MemoSearchMode,
    val showLoading: Boolean,
    val searchFilter: MemoListFilter,
    val shareCardShowTime: Boolean,
    val shareCardShowSignature: Boolean,
    val shareCardSignatureText: String,
    val customFontPath: String?,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val inputToolbarToolOrder: ImmutableList<String>,
    val rootDirectory: String?,
    val imageDirectory: String?,
    val imageMap: ImmutableMap<String, android.net.Uri>,
    val errorMessage: String?,
)
