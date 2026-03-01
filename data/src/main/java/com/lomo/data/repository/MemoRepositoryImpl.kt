package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoTagCount
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.usecase.MemoUpdateAction
import com.lomo.domain.usecase.ResolveMemoUpdateActionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MemoRepositoryImpl
    @Inject
    constructor(
        private val dao: MemoDao,
        private val synchronizer: MemoSynchronizer,
        private val resolveMemoUpdateActionUseCase: ResolveMemoUpdateActionUseCase,
    ) : MemoRepository {
        override fun getAllMemosList(): Flow<List<Memo>> =
            dao
                .getAllMemosFlow()
                .map { entities -> entities.map { it.toDomain() } }
                .flowOn(Dispatchers.Default)

        override suspend fun getRecentMemos(limit: Int): List<Memo> =
            dao.getRecentMemos(limit).map { it.toDomain() }

        override suspend fun getMemosPage(
            limit: Int,
            offset: Int,
        ): List<Memo> =
            if (limit <= 0 || offset < 0) {
                emptyList()
            } else {
                dao.getMemosPage(limit = limit, offset = offset).map { it.toDomain() }
            }

        override suspend fun getMemoCount(): Int = dao.getMemoCountSync()

        override suspend fun refreshMemos() {
            synchronizer.refresh()
        }

        override fun isSyncing(): Flow<Boolean> = synchronizer.isSyncing

        override suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) {
            synchronizer.saveMemoAsync(content, timestamp)
        }

        override suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            when (resolveMemoUpdateActionUseCase(newContent)) {
                MemoUpdateAction.MOVE_TO_TRASH -> synchronizer.deleteMemoAsync(memo)
                MemoUpdateAction.UPDATE_CONTENT -> synchronizer.updateMemoAsync(memo, newContent)
            }
        }

        override suspend fun deleteMemo(memo: Memo) {
            synchronizer.deleteMemoAsync(memo)
        }

        override fun searchMemosList(query: String): Flow<List<Memo>> {
            val trimmed = query.trim()
            val hasCjk =
                trimmed.any {
                    val block =
                        java.lang.Character.UnicodeBlock
                            .of(it)
                    block == java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                        block == java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                        block == java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                        block == java.lang.Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                        block == java.lang.Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT ||
                        block == java.lang.Character.UnicodeBlock.HIRAGANA ||
                        block == java.lang.Character.UnicodeBlock.KATAKANA ||
                        block == java.lang.Character.UnicodeBlock.HANGUL_SYLLABLES
                }

            val source =
                if (hasCjk) {
                    val tokens =
                        com.lomo.data.util.SearchTokenizer
                            .tokenize(trimmed)
                            .split(Regex("\\s+"))
                            .filter { it.isNotBlank() }
                            .distinct()
                            .take(5)
                    if (tokens.isEmpty()) {
                        dao.searchMemosFlow(trimmed)
                    } else {
                        val matchQuery = tokens.joinToString(" OR ") { token -> "$token*" }
                        dao.searchMemosByFtsFlow(matchQuery)
                    }
                } else {
                    dao.searchMemosFlow(trimmed)
                }
            return source
                .map { entities -> entities.map { it.toDomain() } }
                .flowOn(Dispatchers.Default)
        }

        override fun getMemosByTagList(tag: String): Flow<List<Memo>> =
            dao
                .getMemosByTagFlow(tag, "$tag/%")
                .map { entities -> entities.map { it.toDomain() } }
                .flowOn(Dispatchers.Default)

        override fun getMemoCountFlow(): Flow<Int> = dao.getMemoCount()

        override fun getMemoTimestampsFlow(): Flow<List<Long>> = dao.getAllTimestamps()

        override fun getMemoCountByDateFlow(): Flow<Map<String, Int>> =
            dao
                .getMemoCountByDateFlow()
                .map { rows ->
                    buildMap(rows.size) {
                        rows.forEach { row ->
                            put(row.date, row.count)
                        }
                    }
                }.flowOn(Dispatchers.Default)

        override fun getTagCountsFlow(): Flow<List<MemoTagCount>> =
            dao
                .getTagCountsFlow()
                .map { rows ->
                    rows.map { row ->
                        MemoTagCount(name = row.name, count = row.count)
                    }
                }
                .flowOn(Dispatchers.Default)

        override fun getActiveDayCount(): Flow<Int> = dao.getActiveDayCount()

        override fun getDeletedMemosList(): Flow<List<Memo>> =
            dao
                .getDeletedMemosFlow()
                .map { entities -> entities.map { it.toDomain() } }
                .flowOn(Dispatchers.Default)

        override suspend fun restoreMemo(memo: Memo) {
            synchronizer.restoreMemoAsync(memo)
        }

        override suspend fun deletePermanently(memo: Memo) {
            synchronizer.deletePermanently(memo)
        }
    }
