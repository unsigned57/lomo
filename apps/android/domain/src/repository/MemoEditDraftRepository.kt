package com.lomo.domain.repository

import com.lomo.domain.model.MemoEditDraft

/** Durable process-death recovery boundary for one editor session. */
interface MemoEditDraftRepository {
    suspend fun read(): MemoEditDraft?

    suspend fun write(draft: MemoEditDraft)

    suspend fun clear(memoId: String)
}
