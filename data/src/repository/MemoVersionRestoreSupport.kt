package com.lomo.data.repository

import com.lomo.domain.model.Memo

internal interface MemoVersionRestoreSupport {
    suspend fun buildRevisionRestoreCommand(
        currentMemo: Memo,
        revisionId: String,
    ): MemoLifecycleCommand

    suspend fun readRevisionRestoreAssets(revisionId: String): List<MemoRevisionRestoreAsset>

    suspend fun recordRevisionRestoreHandoff(command: MemoLifecycleCommand)
}

internal class JournalMemoVersionRestoreSupport(
    private val journal: MemoVersionJournal,
) : MemoVersionRestoreSupport {
    override suspend fun buildRevisionRestoreCommand(
        currentMemo: Memo,
        revisionId: String,
    ): MemoLifecycleCommand =
        journal.buildRevisionRestoreCommand(
            currentMemo = currentMemo,
            revisionId = revisionId,
        )

    override suspend fun readRevisionRestoreAssets(revisionId: String): List<MemoRevisionRestoreAsset> =
        journal.readRevisionRestoreAssets(revisionId)

    override suspend fun recordRevisionRestoreHandoff(command: MemoLifecycleCommand) {
        journal.recordRevisionRestoreHandoff(command)
    }
}
