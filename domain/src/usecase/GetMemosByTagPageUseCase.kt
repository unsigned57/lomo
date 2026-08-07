package com.lomo.domain.usecase

import androidx.paging.PagingSource
import com.lomo.domain.model.Memo
import com.lomo.domain.model.TagPath
import com.lomo.domain.model.TagSelection
import com.lomo.domain.model.TagSelectionMode
import com.lomo.domain.repository.MemoSearchRepository

class GetMemosByTagPageUseCase(
    private val memoSearchRepository: MemoSearchRepository,
) {
    operator fun invoke(tag: String): PagingSource<Int, Memo> =
        memoSearchRepository.getMemosByTagPagingSource(
            selection = TagSelection(TagPath(tag), TagSelectionMode.Subtree),
        )
}
