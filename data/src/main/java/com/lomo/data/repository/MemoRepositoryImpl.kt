package com.lomo.data.repository

import com.lomo.domain.repository.MemoMutationRepository
import com.lomo.domain.repository.MemoQueryRepository
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.MemoSearchRepository
import com.lomo.domain.repository.MemoTrashRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoRepositoryImpl
    @Inject
    constructor(
        queryRepository: MemoQueryRepositoryImpl,
        mutationRepository: MemoMutationRepositoryImpl,
        searchRepository: MemoSearchRepositoryImpl,
        trashRepository: MemoTrashRepositoryImpl,
    ) : MemoRepository,
        MemoQueryRepository by queryRepository,
        MemoMutationRepository by mutationRepository,
        MemoSearchRepository by searchRepository,
        MemoTrashRepository by trashRepository
