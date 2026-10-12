package com.lomo.app.feature.main

import android.net.Uri
import androidx.collection.LruCache
import com.lomo.domain.model.MediaClass
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import com.lomo.domain.model.markdown.MarkdownAttachment
import com.lomo.domain.model.markdown.MarkdownRenderBlock
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.model.markdown.MarkdownRenderInline
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownWorkspaceRepository
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
import java.util.concurrent.atomic.AtomicLong


class MemoUiMapper(
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
) {
        private val backgroundDispatcher = dispatcherProvider.default

        private val imageContentResolver = MemoUiImageContentResolver()
        private val cacheMutex = Mutex()
        private val cacheHits = AtomicLong()
        private val cacheMisses = AtomicLong()
        private val cacheEvictions = AtomicLong()

        /**
         * Capacity alone decides which rows leave the cache: mapping one screen's collection must
         * never purge rows another screen still renders, so there is no list-driven removal. LRU
         * eviction under [DEFAULT_CACHE_SIZE] is the only shrink path and is counted through
         * `entryRemoved` for observability.
         */
        private val cachedModels =
            object : LruCache<String, CachedMemoUiModel>(DEFAULT_CACHE_SIZE) {
                override fun entryRemoved(
                    evicted: Boolean,
                    key: String,
                    oldValue: CachedMemoUiModel,
                    newValue: CachedMemoUiModel?,
                ) {
                    if (evicted) {
                        cacheEvictions.incrementAndGet()
                    }
                }
            }

        /**
         * Point-in-time cache counters. Hits and misses count every probe (`peek` and the guarded
         * lookup in [mapToCachedUiModel]), so a peek-miss that falls through to a full map records
         * two misses; races can skew totals slightly but never lose a count.
         */
        fun cacheStats(): MemoUiMapperCacheStats =
            MemoUiMapperCacheStats(
                hits = cacheHits.get(),
                misses = cacheMisses.get(),
                evictions = cacheEvictions.get(),
                size = cachedModels.size(),
            )

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
            val displayContent = memo.content
            val processedContent = displayContent
            val resolvedImages =
                imageContentResolver.resolveMemoImages(
                    document = renderDocumentFor(memo, displayContent),
                    imageUrls = memo.imageUrls,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageMap = imageMap,
                )
            val renderDocument = resolvedImages.document
            val imageUrls = resolvedImages.imageUrls
            val presentationPlan =
                com.lomo.ui.component.markdown.buildMarkdownIrPresentationPlan(
                    document = renderDocument,
                    policy = com.lomo.ui.component.markdown.MarkdownPresentationPolicy.MEMO_CARD,
                )
            val shouldShowExpand =
                shouldShowMemoCardExpand(
                    content = displayContent,
                    projectedCharCount = memo.projectedCharCount,
                )
            val collapsedSummary =
                if (processedContent.isBlank()) {
                    buildMemoCardCollapsedSummary(presentationPlan)
                } else {
                    ""
                }

            return MemoUiModel(
                memo = memo,
                processedContent = processedContent,
                renderDocument = renderDocument,
                presentationPlan = presentationPlan,
                tags = memo.tags.toImmutableList(),
                imageUrls = imageUrls,
                shouldShowExpand = shouldShowExpand,
                collapsedSummary = collapsedSummary,
                reminders = reminders.toImmutableList(),
            )
        }

        /**
         * Synchronous cache probe for paging transforms. A hit returns the row without a dispatcher
         * hop and without touching the disk-backed parse; a miss falls through to
         * [mapToCachedUiModel], which performs the parse on the background dispatcher.
         */
        internal fun peekCachedUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            reminders: List<com.lomo.domain.model.ReminderMarker>,
        ): MemoUiModel? {
            val cacheKey = buildCacheKey(memo, rootPath, imagePath, imageMap, reminders)
            // LruCache point reads are internally synchronized; key equality is the
            // consistency guard, so a torn read can only degrade to a miss.
            val cached = cachedModels[memo.id]
            return cached
                ?.takeIf { it.key == cacheKey }
                ?.run {
                    cacheHits.incrementAndGet()
                    model
                }
                ?: run {
                    cacheMisses.incrementAndGet()
                    null
                }
        }

        /**
         * The single cache-aware mapping path for every paging transform. A synchronous peek serves
         * cached rows without a dispatcher hop; a miss performs the parse on the background
         * dispatcher so Paging collection never runs the mapping on the caller's lane.
         */
        internal suspend fun peekOrMapToCachedUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            reminders: List<com.lomo.domain.model.ReminderMarker>,
        ): MemoUiModel =
            peekCachedUiModel(
                memo = memo,
                rootPath = rootPath,
                imagePath = imagePath,
                imageMap = imageMap,
                reminders = reminders,
            ) ?: withContext(backgroundDispatcher) {
                mapToCachedUiModel(
                    memo = memo,
                    rootPath = rootPath,
                    imagePath = imagePath,
                    imageMap = imageMap,
                    reminders = reminders,
                )
            }

        internal suspend fun mapToCachedUiModel(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            reminders: List<com.lomo.domain.model.ReminderMarker>,
        ): MemoUiModel {
            val cacheKey = buildCacheKey(memo, rootPath, imagePath, imageMap, reminders)
            val cached = cacheMutex.withLock { cachedModels[memo.id] }
            if (cached?.key == cacheKey) {
                cacheHits.incrementAndGet()
                return cached.model
            }
            cacheMisses.incrementAndGet()

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

        private fun buildCacheKey(
            memo: Memo,
            rootPath: String?,
            imagePath: String?,
            imageMap: Map<String, Uri>,
            reminders: List<com.lomo.domain.model.ReminderMarker>,
        ): MemoUiCacheKey =
            MemoUiCacheKey(
                memoId = memo.id,
                contentKind = memo.contentKind,
                contentRevision = memo.contentRevision,
                fileFingerprint = memo.fileFingerprint,
                fallbackContent =
                    if (memo.contentRevision == null || memo.fileFingerprint == null) {
                        memo.content
                    } else {
                        null
                    },
                updatedAt = memo.updatedAt,
                isPinned = memo.isPinned,
                isDeleted = memo.isDeleted,
                isPending = memo.isPending,
                tags = memo.tags,
                imageUrls = memo.imageUrls,
                reminderIdentities = reminders.map { reminder ->
                    reminder.reference.opaqueId to reminder.token
                },
                rootPath = rootPath,
                imagePath = imagePath,
                projectedCharCount = memo.projectedCharCount,
                imageDependencySignature =
                    buildImageMapDependencySignatureForPaths(
                        imagePaths = memo.imageUrls.toSet(),
                        imageMap = imageMap,
                    ),
            )

        /**
         * A list row carries only Rust's bounded preview projection. Parsing those bytes as a
         * complete Markdown document would make list work proportional to document complexity and
         * would invent interactive spans from an intentionally incomplete source. Preview rows
         * therefore use one inert text paragraph; full snapshots alone cross the render boundary.
         */
        private fun renderDocumentFor(
            memo: Memo,
            displayContent: String,
        ): MarkdownRenderDocument =
            when (memo.contentKind) {
                MemoContentKind.Full -> markdownWorkspaceRepository.renderMarkdown(displayContent)
                MemoContentKind.Preview -> previewSummaryDocument(memo, displayContent)
            }

        private fun previewSummaryDocument(
            memo: Memo,
            displayContent: String,
        ): MarkdownRenderDocument {
            val sourceByteLength = displayContent.encodeToByteArray().size.toULong()
            val span = MarkdownSourceSpan(startByte = 0uL, endByte = sourceByteLength)
            val blocks =
                if (displayContent.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        MarkdownRenderBlock.Paragraph(
                            sourceSpan = span,
                            inlines = listOf(MarkdownRenderInline.Text(span, displayContent)),
                        ),
                    )
                }
            return MarkdownRenderDocument(
                sourceByteLength = sourceByteLength,
                plainText = displayContent,
                tagNames = memo.tags,
                attachments =
                    memo.imageUrls.map { url ->
                        MarkdownAttachment(destination = url, mediaClass = MediaClass.IMAGE)
                    },
                blocks = blocks,
            )
        }

        private companion object {
            private const val INITIAL_PARALLEL_PRECOMPUTE_COUNT = 6
            private const val DEFAULT_CACHE_SIZE = 256
        }
    }

/** Cache observability surface: hits, misses, LRU evictions and current occupancy. */
data class MemoUiMapperCacheStats(
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val size: Int,
)

private data class MemoUiCacheKey(
    val memoId: String,
    val contentKind: com.lomo.domain.model.MemoContentKind,
    val contentRevision: Long?,
    val fileFingerprint: String?,
    /** Legacy/test memos without a verified revision use body identity until rehydrated. */
    val fallbackContent: String?,
    val updatedAt: Long,
    val isPinned: Boolean,
    val isDeleted: Boolean,
    val isPending: Boolean,
    val tags: List<String>,
    val imageUrls: List<String>,
    val reminderIdentities: List<Pair<String, String>>,
    val rootPath: String?,
    val imagePath: String?,
    val projectedCharCount: Long?,
    val imageDependencySignature: String,
)

private data class CachedMemoUiModel(
    val key: MemoUiCacheKey,
    val model: MemoUiModel,
)
