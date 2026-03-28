package com.lomo.domain.repository

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.model.MemoRevision

interface MemoVersionRepository {
    suspend fun listMemoRevisions(
        memo: Memo,
        cursor: MemoRevisionCursor?,
        limit: Int,
    ): MemoRevisionPage

    suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revisionId: String,
    )
}
