package com.lomo.app.testing.fakes

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoOperationId
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
        attempt: com.lomo.domain.model.MemoCreateAttempt,
    ): Memo = store.addSavedMemo(attempt.content, attempt.timestampMillis)

    override suspend fun updateMemo(
        attempt: com.lomo.domain.model.MemoUpdateAttempt,
    ) = store.replaceMemoContent(attempt.snapshot.memo, attempt.content)

    override suspend fun deleteMemo(
        memo: Memo,
        operationId: MemoOperationId,
    ) = store.moveMemoToDeleted(memo)

    override suspend fun restoreMemoRevision(
        currentMemo: Memo,
        revision: MemoRevision,
        operationId: MemoOperationId,
    ) {
        restoreMemoRevisionCallCount += 1
        lastRestoredMemo = currentMemo
        lastRestoredRevision = revision
        store.restoreMemoRevision(currentMemo, revision)
    }

    override suspend fun setMemoPinned(
        memoId: String,
        pinned: Boolean,
        operationId: MemoOperationId,
    ) = store.updateMemoPinned(memoId, pinned)
}
