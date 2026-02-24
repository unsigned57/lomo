package com.lomo.data.repository

import com.lomo.data.local.dao.MemoDao
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MemoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MemoRepositoryImpl
    @Inject
    constructor(
        private val dao: MemoDao,
        private val synchronizer: MemoSynchronizer,
    ) : MemoRepository {
        override fun getAllMemosList(): Flow<List<Memo>> = dao.getAllMemosFlow().map { entities -> entities.map { it.toDomain() } }

        override suspend fun refreshMemos() {
            synchronizer.refresh()
        }

        override fun isSyncing(): Flow<Boolean> = synchronizer.isSyncing

        override suspend fun saveMemo(
            content: String,
            timestamp: Long,
        ) {
            synchronizer.saveMemo(content, timestamp)
        }

        override suspend fun updateMemo(
            memo: Memo,
            newContent: String,
        ) {
            synchronizer.updateMemo(memo, newContent)
        }

        override suspend fun deleteMemo(memo: Memo) {
            synchronizer.deleteMemo(memo)
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
            return source.map { entities -> entities.map { it.toDomain() } }
        }

        override fun getMemosByTagList(tag: String): Flow<List<Memo>> =
            dao.getMemosByTagFlow(tag, "$tag/%").map { entities -> entities.map { it.toDomain() } }

        override fun getActiveDayCount(): Flow<Int> = dao.getActiveDayCount()

        override fun getDeletedMemosList(): Flow<List<Memo>> = dao.getDeletedMemosFlow().map { entities -> entities.map { it.toDomain() } }

        override suspend fun restoreMemo(memo: Memo) {
            synchronizer.restoreMemo(memo)
        }

        override suspend fun deletePermanently(memo: Memo) {
            synchronizer.deletePermanently(memo)
        }
    }
