package com.lomo.app.testing.fakes

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.repository.MemoVersionRepository

class FakeMemoVersionRepository(
    var pagesByCursor: Map<MemoRevisionCursor?, MemoRevisionPage> = emptyMap()
) : MemoVersionRepository {
    var clearAllMemoSnapshotsCallCount = 0

    override suspend fun listMemoRevisions(
        memo: Memo,
        cursor: MemoRevisionCursor?,
        limit: Int
    ): MemoRevisionPage {
        return pagesByCursor[cursor] ?: MemoRevisionPage(emptyList(), null)
    }

    override suspend fun clearAllMemoSnapshots() {
        clearAllMemoSnapshotsCallCount++
    }
}
