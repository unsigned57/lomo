package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.data.local.dao.MemoPinDao
import com.lomo.data.local.entity.MemoEntity
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoQueryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoQueryRepositoryImpl
    @Inject
    constructor(
        private val memoDao: MemoDao,
        private val memoPinDao: MemoPinDao,
        private val synchronizer: MemoSynchronizer,
    ) : MemoQueryRepository {
        override fun getAllMemosList(): Flow<List<Memo>> =
            combine(
                memoDao.getAllMemosFlow(),
                memoPinDao.getPinnedMemoIdsFlow().map { pinnedIds -> pinnedIds.toSet() },
            ) { entities, pinnedMemoIds ->
                entities.mapToPinnedDomain(pinnedMemoIds)
            }.flowOn(Dispatchers.Default)

        override suspend fun getRecentMemos(limit: Int): List<Memo> {
            val pinnedMemoIds = memoPinDao.getPinnedMemoIds().toSet()
            return memoDao.getRecentMemos(limit).mapToPinnedDomain(pinnedMemoIds)
        }

        override suspend fun getMemosPage(
            limit: Int,
            offset: Int,
        ): List<Memo> =
            if (limit <= 0 || offset < 0) {
                emptyList()
            } else {
                val pinnedMemoIds = memoPinDao.getPinnedMemoIds().toSet()
                memoDao.getMemosPage(limit = limit, offset = offset).mapToPinnedDomain(pinnedMemoIds)
            }

        override suspend fun getMemoCount(): Int = memoDao.getMemoCountSync()

        override suspend fun getMemoById(id: String): Memo? {
            val entity = memoDao.getMemo(id) ?: return null
            val pinnedMemoIds = memoPinDao.getPinnedMemoIds().toSet()
            return entity.toDomain(isPinned = entity.id in pinnedMemoIds)
        }

        override fun isSyncing(): Flow<Boolean> = synchronizer.isSyncing
    }

internal fun List<MemoEntity>.mapToPinnedDomain(pinnedMemoIds: Set<String>): List<Memo> =
    map { entity ->
        entity.toDomain(isPinned = entity.id in pinnedMemoIds)
    }
