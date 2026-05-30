package com.lomo.app.feature.common

import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo

data class MemoCollectionUiState(
    val memos: List<Memo> = emptyList(),
    val uiMemos: List<MemoUiModel> = emptyList(),
    val deletingMemoIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
)
