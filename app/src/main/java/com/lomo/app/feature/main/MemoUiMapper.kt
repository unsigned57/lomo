package com.lomo.app.feature.main

import android.net.Uri
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.buildMemoCardCollapsedSummary
import com.lomo.ui.component.card.shouldShowMemoCardExpand
import com.lomo.ui.component.markdown.ModernMarkdownRenderPlan
import com.lomo.ui.component.markdown.createModernMarkdownRenderPlan
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import javax.inject.Inject

class MemoUiMapper
    internal constructor(
        private val backgroundDispatcher: CoroutineDispatcher,
    ) {
        @Inject
        constructor() : this(Dispatchers.Default)

        private val imageContentResolver = MemoUiImageContentResolver()
        private val cacheMutex = Mutex()
        private val cachedModels =
            LinkedHashMap<String, CachedMemoUiModel>(
                DEFAULT_CACHE_SIZE,
                DEFAULT_CACHE_LOAD_FACTOR,
                true,
            )

        suspend fun mapToUiModels(
            memos: List<Memo>,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            prioritizedMemoIds: Set<String> = emptySet(),
        ): List<MemoUiModel> =
            withContext(backgroundDispatcher) {
                if (memos.isEmpty()) {
                    return@withContext emptyList()
                }

                val prioritizedIds =
                    if (prioritizedMemoIds.isNotEmpty()) {
                        prioritizedMemoIds
                    } else {
                        memos
                            .asSequence()
                            .take(DEFAULT_PRIORITY_WINDOW_SIZE)
                            .map { it.id }
                            .toSet()
                    }

                val currentMemoIds = memos.asSequence().map(Memo::id).toSet()
                cacheMutex.withLock {
                    cachedModels.keys.retainAll(currentMemoIds)
                }

                val results = ArrayList<MemoUiModel>(memos.size)
                for (memo in memos) {
                    val precomputeMarkdown = memo.id in prioritizedIds
                    results +=
                        mapToCachedUiModel(
                            memo = memo,
                            rootPath = rootPath,
                            imagePath = imagePath,
                            imageMap = imageMap,
                            precomputeMarkdown = precomputeMarkdown,
                        )
                }
                results
            }

        fun mapToUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            precomputeMarkdown: Boolean = true,
            existingRenderPlan: ModernMarkdownRenderPlan? = null,
            existingProcessedContent: String? = null,
        ): MemoUiModel {
            val displayContent = appendLegacyMemoGeoLocation(memo.content, memo.geoLocation)
            val processedContent = buildProcessedContent(displayContent, rootPath, imagePath, imageMap)
            val canReuseExistingRenderPlan =
                existingRenderPlan != null &&
                    existingProcessedContent != null &&
                    existingProcessedContent == processedContent
            val renderPlan =
                when {
                    canReuseExistingRenderPlan -> existingRenderPlan
                    precomputeMarkdown ->
                        createModernMarkdownRenderPlan(
                            content = processedContent,
                            knownTagsToStrip = memo.tags,
                        )

                    else -> null
                }
            val imageUrls = imageContentResolver.extractImageUrls(processedContent)
            val shouldShowExpand = shouldShowMemoCardExpand(displayContent)
            val collapsedSummary = buildMemoCardCollapsedSummary(displayContent, memo.tags)

            return MemoUiModel(
                memo = memo,
                processedContent = processedContent,
                precomputedRenderPlan = renderPlan,
                tags = memo.tags.toImmutableList(),
                imageUrls = imageUrls,
                shouldShowExpand = shouldShowExpand,
                collapsedSummary = collapsedSummary,
            )
        }

        private fun buildProcessedContent(
            content: String,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
        ): String =
            imageContentResolver.buildProcessedContent(
                content = content,
                rootPath = rootPath,
                imagePath = imagePath,
                imageMap = imageMap,
            )

        private suspend fun mapToCachedUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            precomputeMarkdown: Boolean,
        ): MemoUiModel {
            val displayContent = appendLegacyMemoGeoLocation(memo.content, memo.geoLocation)
            val cacheKey =
                MemoUiCacheKey(
                    memo = memo,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageDependencySignature =
                        buildMemoUiImageDependencySignature(
                            content = displayContent,
                            imageMap = imageMap,
                        ),
                )
            val cached = cacheMutex.withLock { cachedModels[memo.id] }
            if (cached?.key == cacheKey && (!precomputeMarkdown || cached.model.precomputedRenderPlan != null)) {
                return cached.model
            }

            val uiModel =
                mapToUiModel(
                    memo = memo,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageMap = imageMap,
                    precomputeMarkdown = precomputeMarkdown,
                    existingRenderPlan = cached?.model?.precomputedRenderPlan,
                    existingProcessedContent = cached?.model?.processedContent,
                )
            cacheMutex.withLock {
                cachedModels[memo.id] =
                    CachedMemoUiModel(
                        key = cacheKey,
                        model = uiModel,
                    )
                trimCacheIfNeeded()
            }
            return uiModel
        }

        private fun trimCacheIfNeeded() {
            while (cachedModels.size > DEFAULT_CACHE_SIZE) {
                val eldestKey = cachedModels.entries.firstOrNull()?.key ?: return
                cachedModels.remove(eldestKey)
            }
        }

        private companion object {
            private const val DEFAULT_PRIORITY_WINDOW_SIZE = 20
            private const val DEFAULT_CACHE_SIZE = 256
            private const val DEFAULT_CACHE_LOAD_FACTOR = 0.75f
        }
    }

private data class MemoUiCacheKey(
    val memo: Memo,
    val rootPath: String?,
    val imagePath: String?,
    val imageDependencySignature: String,
)

private data class CachedMemoUiModel(
    val key: MemoUiCacheKey,
    val model: MemoUiModel,
)
