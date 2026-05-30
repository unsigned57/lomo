package com.lomo.data.repository

import androidx.paging.PagingSource
import com.lomo.data.local.dao.MemoSearchDao
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoSearchRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoSearchRepositoryImpl
    @Inject
    constructor(
        private val memoSearchDao: MemoSearchDao,
    ) : MemoSearchRepository {
        override fun getMemosByTagPagingSource(tag: String): PagingSource<Int, Memo> =
            MemoRowMappingPagingSource(
                memoSearchDao.getMemosByTagPagingSource(tag = tag, tagPrefix = "$tag/%"),
            )
    }
