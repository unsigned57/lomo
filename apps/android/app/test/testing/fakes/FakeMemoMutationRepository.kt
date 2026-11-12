package com.lomo.app.testing.fakes

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoOperationId
import com.lomo.domain.model.MemoRevision
import com.lomo.domain.repository.MemoMutationRepository

class FakeMemoMutationRepository(private val store: FakeMemoStore) : MemoMutationRepository {
    var restoreMemoRevisionCallCount: Int = 0
        private set
    var lastRestoredMemo: Memo? = null
        private set
    var lastRestoredRevision: MemoRevision? = null
        private set

    override suspend fun refreshMemos() = store.recordMemoRefresh()

    override suspend fun saveMemo(
        attempt: com.lomo.domain.model.MemoCreateAttempt,
        onAccepted: (String) -> Unit,
    ): Memo = store.addSavedMemo(attempt.content, attempt.timestampMillis).also { onAccepted(it.id) }

    override suspend fun updateMemo(attempt: com.lomo.domain.model.MemoUpdateAttempt) =
        store.replaceMemoContent(attempt.snapshot.memo, attempt.content)

    override suspend fun deleteMemo(memo: Memo, operationId: MemoOperationId) = store.moveMemoToDeleted(memo)

    override suspend fun restoreMemoRevision(currentMemo: Memo, revision: MemoRevision, operationId: MemoOperationId) {
        restoreMemoRevisionCallCount += 1
        lastRestoredMemo = currentMemo
        lastRestoredRevision = revision
        store.restoreMemoRevision(currentMemo, revision)
    }

    override suspend fun setMemoPinned(memoId: String, pinned: Boolean, operationId: MemoOperationId) =
        store.updateMemoPinned(memoId, pinned)
}
