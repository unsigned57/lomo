package com.lomo.domain.repository

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage

interface MemoVersionRepository {
    suspend fun listMemoRevisions(memo: Memo, cursor: MemoRevisionCursor?, limit: Int): MemoRevisionPage
}
