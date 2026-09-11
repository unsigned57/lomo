package com.lomo.app.testing.fakes

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.repository.MemoMutationRepository

class FakeMemoMutationRepository(
    private val store: FakeMemoStore,
) : MemoMutationRepository {
    var documentMutationCallCount: Int = 0
        private set
    var restoreMemoRevisionCallCount: Int = 0
        private set
    var lastRestoredMemo: Memo? = null
        private set
    var lastRestoredRevision: MemoRevision? = null
        private set

    override suspend fun refreshMemos() = store.recordMemoRefresh()

    override suspend fun commitDocumentMutation(
        mutation: com.lomo.domain.model.MemoDocumentMutation,
    ) {
        documentMutationCallCount += 1
        val current = store.currentActiveMemos().firstOrNull { it.id == mutation.facts.memoId }
        if (current != null) {
            store.replaceMemoContent(current, mutation.facts.content)
        }
    }

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
