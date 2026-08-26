package com.lomo.domain.testing.fakes

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.repository.MemoMutationRepository

class FakeMemoMutationRepository(
    private val store: FakeMemoStore,
) : MemoMutationRepository {
    var restoreMemoRevisionCallCount: Int = 0
        private set
    var lastRestoredMemo: Memo? = null
        private set
    var lastRestoredRevision: MemoRevision? = null
        private set

    override suspend fun refreshMemos() = store.recordMemoRefresh()

    override suspend fun saveMemo(
        content: String,
        timestamp: Long,
        geoLocation: String?,
    ): Memo = store.addSavedMemo(content, timestamp, geoLocation)

    override suspend fun updateMemo(
        memo: Memo,
        newContent: String,
    ) = store.replaceMemoContent(memo, newContent)

    override suspend fun deleteMemo(memo: Memo) = store.moveMemoToDeleted(memo)

    override suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revision: MemoRevision,
    ) {
        restoreMemoRevisionCallCount += 1
        lastRestoredMemo = currentMemo
        lastRestoredRevision = revision
        store.restoreMemoRevision(currentMemo, revision)
    }

    override suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
    ) = store.updateMemoPinned(memoId, pinned)
}
