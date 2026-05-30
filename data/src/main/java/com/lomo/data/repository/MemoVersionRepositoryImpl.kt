package com.lomo.data.repository

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoRevisionCursor
import com.lomo.domain.model.MemoRevisionPage
import com.lomo.domain.repository.MemoVersionRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoVersionRepositoryImpl
    @Inject
    constructor(
        private val journal: MemoVersionJournal,
    ) : MemoVersionRepository {
        override suspend fun listMemoRevisions(
            memo: Memo,
            cursor: MemoRevisionCursor?,
            limit: Int,
        ): MemoRevisionPage =
            journal.listMemoRevisions(
                memo = memo,
                cursor = cursor,
                limit = limit,
            )

        override suspend fun clearAllMemoSnapshots() {
            journal.clearAllMemoSnapshots()
        }
    }
