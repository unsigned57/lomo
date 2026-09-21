package com.lomo.domain.repository

import com.lomo.domain.model.MemoCreateDraft

/** Durable process-death recovery boundary for the unsaved new-memo draft. */
interface MemoCreateDraftRepository {
    suspend fun read(): MemoCreateDraft?

    suspend fun write(draft: MemoCreateDraft)

    suspend fun clear()
}
