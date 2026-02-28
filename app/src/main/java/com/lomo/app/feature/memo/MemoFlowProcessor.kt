package com.lomo.app.feature.memo

import android.net.Uri
import com.lomo.app.feature.main.MemoUiMapper
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MemoFlowProcessor
    @Inject
    constructor(
        private val mapper: MemoUiMapper,
    ) {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun mapMemoFlow(
            memos: Flow<List<Memo>>,
            rootDirectory: Flow<String?>,
            imageDirectory: Flow<String?>,
            imageMap: Flow<Map<String, Uri>>,
            prioritizeMemoIds: Flow<Set<String>> = flowOf(emptySet()),
        ): Flow<List<MemoUiModel>> {
            val memoCache = createMemoCache()
            return combine(
                memos,
                rootDirectory,
                imageDirectory,
                imageMap,
                prioritizeMemoIds,
            ) { currentMemos, rootDir, imageDir, currentImageMap, currentPriorityIds ->
                MappingContext(
                    memos = currentMemos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                    prioritizedMemoIds = currentPriorityIds,
                )
            }.distinctUntilChanged()
                .mapLatest { context -> mapWithCache(context, memoCache) }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun mapMemoSnapshot(
            memos: List<Memo>,
            rootDirectory: Flow<String?>,
            imageDirectory: Flow<String?>,
            imageMap: Flow<Map<String, Uri>>,
        ): Flow<List<MemoUiModel>> {
            val memoCache = createMemoCache()
            return combine(rootDirectory, imageDirectory, imageMap) { rootDir, imageDir, currentImageMap ->
                MappingContext(
                    memos = memos,
                    rootDirectory = rootDir,
                    imageDirectory = imageDir,
                    imageMap = currentImageMap,
                    prioritizedMemoIds = emptySet(),
                )
            }.distinctUntilChanged()
                .mapLatest { context -> mapWithCache(context, memoCache) }
        }

        private suspend fun mapWithCache(
            context: MappingContext,
            memoCache: LinkedHashMap<String, CachedMemoUiModel>,
        ): List<MemoUiModel> =
            withContext(Dispatchers.Default) {
                if (context.memos.isEmpty()) {
                    memoCache.clear()
                    return@withContext emptyList()
                }

                val environment =
                    MappingEnvironment(
                        rootDirectory = context.rootDirectory,
                        imageDirectory = context.imageDirectory,
                        imageMapSize = context.imageMap.size,
                        imageMapIdentity = System.identityHashCode(context.imageMap),
                    )
                val prioritizedIds =
                    if (context.prioritizedMemoIds.isNotEmpty()) {
                        context.prioritizedMemoIds
                    } else {
                        context.memos
                            .asSequence()
                            .take(DEFAULT_PRIORITY_WINDOW_SIZE)
                            .map { it.id }
                            .toSet()
                    }
                val cacheableIds = buildCacheableIds(context.memos, prioritizedIds)
                val activeIds = HashSet<String>(context.memos.size)
                val uiModels = ArrayList<MemoUiModel>(context.memos.size)

                context.memos.forEach { memo ->
                    activeIds += memo.id
                    val shouldCacheEntry = memo.id in cacheableIds
                    val cached = if (shouldCacheEntry) memoCache[memo.id] else null
                    val shouldPrecompute = memo.id in prioritizedIds
                    if (cached != null &&
                        cached.memo == memo &&
                        cached.environment == environment &&
                        (!shouldPrecompute || cached.uiModel.markdownNode != null)
                    ) {
                        uiModels += cached.uiModel
                    } else {
                        val mapped =
                            mapper.mapToUiModel(
                                memo = memo,
                                rootPath = context.rootDirectory,
                                imagePath = context.imageDirectory,
                                imageMap = context.imageMap,
                                precomputeMarkdown = shouldPrecompute,
                                existingNode = cached?.uiModel?.markdownNode,
                                existingProcessedContent = cached?.uiModel?.processedContent,
                            )
                        if (shouldCacheEntry) {
                            memoCache[memo.id] =
                                CachedMemoUiModel(
                                    memo = memo,
                                    environment = environment,
                                    uiModel = mapped,
                                )
                        } else {
                            memoCache.remove(memo.id)
                        }
                        uiModels += mapped
                    }
                }

                pruneStaleCacheEntries(memoCache, activeIds)
                trimCache(memoCache)
                uiModels
            }

        private fun createMemoCache(): LinkedHashMap<String, CachedMemoUiModel> = LinkedHashMap(INITIAL_CACHE_CAPACITY, 0.75f, true)

        private fun buildCacheableIds(
            memos: List<Memo>,
            prioritizedIds: Set<String>,
        ): Set<String> {
            if (memos.isEmpty()) return emptySet()

            val ids =
                LinkedHashSet<String>(
                    (prioritizedIds.size + FRONT_CACHE_WINDOW_SIZE).coerceAtMost(MAX_CACHE_SIZE),
                )
            ids.addAll(prioritizedIds)

            memos
                .asSequence()
                .take(FRONT_CACHE_WINDOW_SIZE)
                .mapTo(ids) { it.id }

            return ids
        }

        private fun pruneStaleCacheEntries(
            memoCache: LinkedHashMap<String, CachedMemoUiModel>,
            activeIds: Set<String>,
        ) {
            val iterator = memoCache.entries.iterator()
            while (iterator.hasNext()) {
                val id = iterator.next().key
                if (id !in activeIds) {
                    iterator.remove()
                }
            }
        }

        private fun trimCache(memoCache: LinkedHashMap<String, CachedMemoUiModel>) {
            while (memoCache.size > MAX_CACHE_SIZE) {
                val iterator = memoCache.entries.iterator()
                if (!iterator.hasNext()) return
                iterator.next()
                iterator.remove()
            }
        }

        private data class MappingContext(
            val memos: List<Memo>,
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMap: Map<String, Uri>,
            val prioritizedMemoIds: Set<String>,
        )

        private data class MappingEnvironment(
            val rootDirectory: String?,
            val imageDirectory: String?,
            val imageMapSize: Int,
            val imageMapIdentity: Int,
        )

        private data class CachedMemoUiModel(
            val memo: Memo,
            val environment: MappingEnvironment,
            val uiModel: MemoUiModel,
        )

        private companion object {
            private const val INITIAL_CACHE_CAPACITY = 256
            private const val MAX_CACHE_SIZE = 768
            private const val DEFAULT_PRIORITY_WINDOW_SIZE = 20
            private const val FRONT_CACHE_WINDOW_SIZE = 180
        }
    }
