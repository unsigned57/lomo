package com.lomo.app.feature.main

import android.net.Uri
import androidx.collection.LruCache
import com.lomo.domain.model.Memo
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MarkdownReminderRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.ui.component.card.buildMemoCardCollapsedSummary
import com.lomo.ui.component.card.shouldShowMemoCardExpand
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap


class MemoUiMapper(
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
    private val markdownReminderRepository: MarkdownReminderRepository,
) {
        private val backgroundDispatcher = dispatcherProvider.default

        private val imageContentResolver = MemoUiImageContentResolver()
        private val cacheMutex = Mutex()
        private val cachedModels = LruCache<String, CachedMemoUiModel>(DEFAULT_CACHE_SIZE)

        suspend fun mapToUiModels(
            memos: List<Memo>,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
        ): List<MemoUiModel> =
            withContext(backgroundDispatcher) {
                if (memos.isEmpty()) {
                    return@withContext emptyList()
                }

                val currentMemoIds = memos.asSequence().map(Memo::id).toSet()
                cacheMutex.withLock {
                    cachedModels.snapshot().keys
                        .filterNot(currentMemoIds::contains)
                        .forEach(cachedModels::remove)
                }

                coroutineScope {
                    val startupParallelDispatcher = backgroundDispatcher.limitedParallelism(2)
                    val startupMappedById =
                        memos
                            .take(INITIAL_PARALLEL_PRECOMPUTE_COUNT)
                            .map { memo ->
                                async(startupParallelDispatcher) {
                                    memo.id to
                                        mapToCachedUiModel(
                                            memo = memo,
                                            rootPath = rootPath,
                                            imagePath = imagePath,
                                            imageMap = imageMap,
                                            reminders = memo.reminders,
                                        )
                                }
                            }.awaitAll()
                            .toMap(LinkedHashMap(INITIAL_PARALLEL_PRECOMPUTE_COUNT))

                    val results = ArrayList<MemoUiModel>(memos.size)
                    memos.take(INITIAL_PARALLEL_PRECOMPUTE_COUNT).forEach { memo ->
                        results += checkNotNull(startupMappedById[memo.id])
                    }
                    memos.drop(INITIAL_PARALLEL_PRECOMPUTE_COUNT).forEach { memo ->
                        results +=
                            mapToCachedUiModel(
                                memo = memo,
                                rootPath = rootPath,
                                imagePath = imagePath,
                                imageMap = imageMap,
                                reminders = memo.reminders,
                            )
                    }
                    results
                }
            }

        fun mapToUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            reminders: List<com.lomo.domain.model.ReminderMarker> = memo.reminders,
        ): MemoUiModel {
            val displayContent = appendLegacyMemoGeoLocation(memo.content, memo.geoLocation)
            val processedContent = displayContent
            val renderDocument =
                imageContentResolver.resolveRenderDocumentImages(
                    document = markdownWorkspaceRepository.renderMarkdown(displayContent),
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageMap = imageMap,
                )
            val imageUrls =
                imageContentResolver.resolveProjectedImageUrls(
                    imageUrls = memo.imageUrls,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageMap = imageMap,
                )
            val shouldShowExpand = shouldShowMemoCardExpand(displayContent)
            val collapsedSummary = buildMemoCardCollapsedSummary(renderDocument)

            return MemoUiModel(
                memo = memo,
                processedContent = processedContent,
                renderDocument = renderDocument,
                tags = memo.tags.toImmutableList(),
                imageUrls = imageUrls,
                shouldShowExpand = shouldShowExpand,
                collapsedSummary = collapsedSummary,
                reminders = reminders.toImmutableList(),
            )
        }

        private suspend fun mapToCachedUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            reminders: List<com.lomo.domain.model.ReminderMarker>,
        ): MemoUiModel {
            val displayContent = appendLegacyMemoGeoLocation(memo.content, memo.geoLocation)
            val cacheKey =
                MemoUiCacheKey(
                    memo = memo,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageDependencySignature =
                        buildImageMapDependencySignatureForPaths(
                            imagePaths = memo.imageUrls.filterNot(::isAudioAttachmentPath).toSet(),
                            imageMap = imageMap,
                        ),
                )
            val cached = cacheMutex.withLock { cachedModels[memo.id] }
            if (cached?.key == cacheKey) {
                return cached.model
            }

            val uiModel =
                mapToUiModel(
                    memo = memo,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageMap = imageMap,
                    reminders = reminders,
                )
            cacheMutex.withLock {
                cachedModels.put(
                    memo.id,
                    CachedMemoUiModel(
                        key = cacheKey,
                        model = uiModel,
                    ),
                )
            }
            return uiModel
        }

        private companion object {
            private const val INITIAL_PARALLEL_PRECOMPUTE_COUNT = 6
            private const val DEFAULT_CACHE_SIZE = 256
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
