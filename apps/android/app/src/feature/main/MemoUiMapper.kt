package com.lomo.app.feature.main

import android.net.Uri
import androidx.collection.LruCache
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import com.lomo.domain.model.MemoPreviewRenderRequest
import com.lomo.domain.model.MemoPreviewRenderState
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.ui.component.card.buildMemoCardCollapsedSummary
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

class MemoUiMapper(
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val markdownWorkspaceRepository: MarkdownWorkspaceRepository,
    private val workspaceAuthorityProvider: () -> WorkspaceAuthority? = { null },
) {
    private val backgroundDispatcher = dispatcherProvider.default

    private val imageContentResolver = MemoUiImageContentResolver()
    private val cacheMutex = Mutex()
    private val rawCacheLock = Any()
    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val cacheEvictions = AtomicLong()
    private val rawRenderHits = AtomicLong()
    private val rawRenderMisses = AtomicLong()
    private val preRenderedHits = AtomicLong()
    private val imageDependencyRemaps = AtomicLong()

    /**
     * Layer 1 cache: raw owner Markdown IR keyed independently of image-map / path resolution so
     * media inventory refreshes only re-run image presentation without re-rendering Markdown.
     */
    private val rawRenderCache = LruCache<RawRenderCacheKey, CachedRawRenderDocument>(DEFAULT_CACHE_SIZE)

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
    fun cacheStats(): MemoUiMapperCacheStats = MemoUiMapperCacheStats(
        hits = cacheHits.get(),
        misses = cacheMisses.get(),
        evictions = cacheEvictions.get(),
        size = cachedModels.size(),
        rawRenderHits = rawRenderHits.get(),
        rawRenderMisses = rawRenderMisses.get(),
        preRenderedHits = preRenderedHits.get(),
        imageDependencyRemaps = imageDependencyRemaps.get(),
        rawCacheSize = synchronized(rawCacheLock) { rawRenderCache.size() },
    )

    suspend fun prefetchPreviews(memos: List<Memo>) {
        if (memos.isEmpty()) return
        val authority = workspaceAuthorityProvider()
        val pendingMemos = ArrayList<Memo>()
        synchronized(rawCacheLock) {
            for (memo in memos) {
                if (memo.contentKind != MemoContentKind.Preview ||
                    memo.previewRenderState !is MemoPreviewRenderState.OnDemand ||
                    memo.contentRevision == null
                ) {
                    continue
                }
                val rawKey = buildRawRenderKey(memo, authority)
                val cached = rawRenderCache[rawKey]
                if (cached == null || cached.exactContent != memo.content) {
                    pendingMemos += memo
                }
            }
        }
        if (pendingMemos.isEmpty()) return
        val distinctRequests =
            pendingMemos
                .distinctBy { memo -> buildRawRenderKey(memo, authority) }
                .map { memo ->
                    MemoPreviewRenderRequest(
                        memoId = memo.id,
                        contentRevision = checkNotNull(memo.contentRevision),
                        expectedBodyPreview = memo.content,
                        contentKind = memo.contentKind,
                    )
                }
        val rendered = markdownWorkspaceRepository.loadMemoPreviews(distinctRequests)
        if (rendered.isEmpty() || workspaceAuthorityProvider() != authority) return
        synchronized(rawCacheLock) {
            for (memo in pendingMemos) {
                val doc = rendered[memo.id] ?: continue
                rawRenderCache.put(
                    buildRawRenderKey(memo, authority),
                    CachedRawRenderDocument(exactContent = memo.content, document = doc),
                )
            }
        }
    }

    suspend fun mapToUiModels(
        memos: List<Memo>,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
    ): List<MemoUiModel> = withContext(backgroundDispatcher) {
        if (memos.isEmpty()) {
            return@withContext emptyList()
        }
        prefetchPreviews(memos)

        coroutineScope {
            val startupParallelDispatcher = backgroundDispatcher.limitedParallelism(2)
            val startupMappedById =
                memos
                    .take(INITIAL_PARALLEL_PRECOMPUTE_COUNT)
                    .map { memo ->
                        async(startupParallelDispatcher) {
                            memo.id to
                                mapCachedModelInBackground(
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
                    mapCachedModelInBackground(
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
        val rawDocument = resolveRawRenderDocument(memo, workspaceAuthorityProvider())
        return assembleUiModel(
            memo = memo,
            rawDocument = rawDocument,
            rootPath = rootPath,
            imagePath = imagePath,
            imageMap = imageMap,
            reminders = reminders,
        )
    }

    private fun assembleUiModel(
        memo: Memo,
        rawDocument: MarkdownRenderDocument,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
        reminders: List<com.lomo.domain.model.ReminderMarker>,
    ): MemoUiModel {
        val processedContent = memo.content
        val resolvedImages =
            imageContentResolver.resolveMemoImages(
                document = rawDocument,
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
            memo.isTruncatedPreview()
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
        val authority = workspaceAuthorityProvider()
        val cacheKey = buildCacheKey(memo, rootPath, imagePath, imageMap, reminders, authority)
        // LruCache point reads are internally synchronized; key equality is the
        // consistency guard, so a torn read can only degrade to a miss.
        val cached = cachedModels[memo.id]
        return cached
            ?.reuseFor(cacheKey)
            ?.also { cacheHits.incrementAndGet() }
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
    ): MemoUiModel = peekCachedUiModel(
        memo = memo,
        rootPath = rootPath,
        imagePath = imagePath,
        imageMap = imageMap,
        reminders = reminders,
    ) ?: mapToCachedUiModel(
        memo = memo,
        rootPath = rootPath,
        imagePath = imagePath,
        imageMap = imageMap,
        reminders = reminders,
    )

    internal suspend fun mapToCachedUiModel(
        memo: Memo,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
        reminders: List<com.lomo.domain.model.ReminderMarker>,
    ): MemoUiModel = withContext(backgroundDispatcher) {
        mapCachedModelInBackground(memo, rootPath, imagePath, imageMap, reminders)
    }

    // Batch mapping already owns its dispatcher, including the two-worker startup bound.
    // Keep its workers on that lane instead of escaping back to the unrestricted dispatcher.
    private suspend fun mapCachedModelInBackground(
        memo: Memo,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
        reminders: List<com.lomo.domain.model.ReminderMarker>,
    ): MemoUiModel {
        val authority = workspaceAuthorityProvider()
        val cacheKey = buildCacheKey(memo, rootPath, imagePath, imageMap, reminders, authority)
        val cached = cacheMutex.withLock { cachedModels[memo.id] }
        val reused = cached?.reuseFor(cacheKey)
        if (reused != null) {
            cacheHits.incrementAndGet()
            cacheMutex.withLock { cachedModels.put(memo.id, CachedMemoUiModel(cacheKey, reused)) }
            return reused
        }
        if (cached != null && cached.key.renderInputs.hasOnlyImageDependencyChange(cacheKey.renderInputs)) {
            imageDependencyRemaps.incrementAndGet()
        }
        cacheMisses.incrementAndGet()
        val rawDocument = resolveRawRenderDocument(memo, authority)
        val uiModel =
            assembleUiModel(
                memo = memo,
                rawDocument = rawDocument,
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

    private fun resolveRawRenderDocument(memo: Memo, authority: WorkspaceAuthority?): MarkdownRenderDocument {
        val rawKey = buildRawRenderKey(memo, authority)
        val preRendered = memo.previewRenderState as? MemoPreviewRenderState.Ready
        if (preRendered != null) {
            preRenderedHits.incrementAndGet()
            rawRenderHits.incrementAndGet()
            synchronized(rawCacheLock) {
                rawRenderCache.put(
                    rawKey,
                    CachedRawRenderDocument(exactContent = memo.content, document = preRendered.document),
                )
            }
            return preRendered.document
        }
        synchronized(rawCacheLock) {
            rawRenderCache[rawKey]
                ?.takeIf { entry -> entry.exactContent == memo.content }
                ?.let { hit ->
                    rawRenderHits.incrementAndGet()
                    return hit.document
                }
        }
        rawRenderMisses.incrementAndGet()
        val rendered = markdownWorkspaceRepository.renderMarkdown(memo.content)
        synchronized(rawCacheLock) {
            rawRenderCache.put(
                rawKey,
                CachedRawRenderDocument(exactContent = memo.content, document = rendered),
            )
        }
        return rendered
    }

    private fun buildRawRenderKey(memo: Memo, authority: WorkspaceAuthority?): RawRenderCacheKey = RawRenderCacheKey(
        workspaceId = authority?.workspaceId,
        generation = authority?.generation,
        memoId = memo.id,
        contentRevision = memo.contentRevision,
        contentKind = memo.contentKind,
        schemaVersion = MarkdownRenderDocument.SCHEMA_VERSION,
    )

    private fun buildCacheKey(
        memo: Memo,
        rootPath: String?,
        imagePath: String?,
        imageMap: Map<String, Uri>,
        reminders: List<com.lomo.domain.model.ReminderMarker>,
        authority: WorkspaceAuthority?,
    ): MemoUiCacheKey = MemoUiCacheKey(
        memo = memo,
        reminders = reminders.toList(),
        renderInputs = MemoUiRenderInputs(
            workspaceId = authority?.workspaceId,
            generation = authority?.generation,
            content = memo.content,
            contentKind = memo.contentKind,
            contentRevision = memo.contentRevision,
            schemaVersion = MarkdownRenderDocument.SCHEMA_VERSION,
            imageUrls = memo.imageUrls.toList(),
            rootPath = rootPath,
            imagePath = imagePath,
            projectedCharCount = memo.projectedCharCount,
            imageDependencySignature = buildImageMapDependencySignatureForPaths(
                imagePaths = memo.imageUrls.toSet(),
                imageMap = imageMap,
            ),
        ),
    )

    private companion object {
        private const val INITIAL_PARALLEL_PRECOMPUTE_COUNT = 6
        private const val DEFAULT_CACHE_SIZE = 256
    }
}

/**
 * A preview row hides the rest of its memo exactly when the store's UTF-16 char count of the whole
 * body exceeds the preview's own length; expanding is then the only way to reach the cut content.
 */
private fun Memo.isTruncatedPreview(): Boolean = contentKind == MemoContentKind.Preview &&
    projectedCharCount?.let { total -> total > content.length } == true

/** Cache observability surface: hits, misses, LRU evictions and split raw-IR metrics. */
data class MemoUiMapperCacheStats(
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val size: Int,
    val rawRenderHits: Long = 0,
    val rawRenderMisses: Long = 0,
    val preRenderedHits: Long = 0,
    val imageDependencyRemaps: Long = 0,
    val rawCacheSize: Int = 0,
)

private data class RawRenderCacheKey(
    val workspaceId: String?,
    val generation: Long?,
    val memoId: String,
    val contentRevision: Long?,
    val contentKind: MemoContentKind,
    val schemaVersion: UInt,
)

private data class CachedRawRenderDocument(val exactContent: String, val document: MarkdownRenderDocument)

private data class MemoUiCacheKey(
    val memo: Memo,
    val reminders: List<com.lomo.domain.model.ReminderMarker>,
    val renderInputs: MemoUiRenderInputs,
)

/** Only inputs consumed by Markdown/media rendering; metadata never invalidates this layer. */
private data class MemoUiRenderInputs(
    val workspaceId: String?,
    val generation: Long?,
    val content: String,
    val contentKind: MemoContentKind,
    val contentRevision: Long?,
    val schemaVersion: UInt,
    val imageUrls: List<String>,
    val rootPath: String?,
    val imagePath: String?,
    val projectedCharCount: Long?,
    val imageDependencySignature: String,
) {
    fun hasOnlyImageDependencyChange(other: MemoUiRenderInputs): Boolean = workspaceId == other.workspaceId &&
        generation == other.generation &&
        content == other.content &&
        contentKind == other.contentKind &&
        contentRevision == other.contentRevision &&
        schemaVersion == other.schemaVersion &&
        projectedCharCount == other.projectedCharCount &&
        this != other
}

private data class CachedMemoUiModel(val key: MemoUiCacheKey, val model: MemoUiModel)

/** Rendering identity is independent of the persistence fingerprint carried by row actions. */
private fun CachedMemoUiModel.reuseFor(current: MemoUiCacheKey): MemoUiModel? = when {
    key == current -> model

    key.renderInputs != current.renderInputs -> null

    else -> model.copy(
        memo = current.memo,
        tags = current.memo.tags.toImmutableList(),
        reminders = current.reminders.toImmutableList(),
    )
}
