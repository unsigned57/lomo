package com.lomo.data.repository

import com.lomo.domain.model.Memo

internal class RestoreMemoRevisionMutationDelegate(
    private val runtime: MemoMutationRuntime,
) : MemoRevisionRestoreMutationOperations {
    override suspend fun restoreMemoRevisionInDb(
        currentMemo: Memo,
        revisionId: String,
    ): Long =
        runtime.mutationGate.withLock {
            val command =
                runtime.memoVersionRestoreSupport.buildRevisionRestoreCommand(
                    currentMemo = currentMemo,
                    revisionId = revisionId,
                )
            restoreMemoRevisionWithOutbox(
                daoBundle = runtime.daoBundle,
                command = command,
            )
        }
}
