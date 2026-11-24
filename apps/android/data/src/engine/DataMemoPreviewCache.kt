package com.lomo.data.engine

import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import com.lomo.domain.model.MemoPreviewRenderRequest
import com.lomo.domain.model.MemoPreviewRenderState
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.nativebridge.SessionMemoPreviewOutcome
import com.lomo.nativebridge.SessionMemoPreviewRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private const val PREVIEW_CACHE_MAX_SIZE = 256
private const val PREVIEW_BATCH_LIMIT = 8
internal const val DEFAULT_LEADING_PREVIEW_WINDOW = 20

internal data class RawPreviewCacheKey(
    val workspaceId: String,
    val generation: Long,
    val memoId: String,
    val contentRevision: Long,
    val contentKind: MemoContentKind,
    val schemaVersion: UInt,
)

private data class CachedRawPreview(val bodyPreview: String, val document: MarkdownRenderDocument)

internal data class DataMemoPreviewCacheStats(
    val hits: Long,
    val misses: Long,
    val coalescedHits: Long,
    val batchCalls: Long,
    val renderedItems: Long,
    val revisionMismatches: Long,
    val evictions: Long,
    val size: Int,
)

private data class PendingPreviewEntry(
    val key: RawPreviewCacheKey,
    val request: MemoPreviewRenderRequest,
    val expectedByteLength: ULong,
    val deferred: CompletableDeferred<MarkdownRenderDocument?>,
)

/**
 * Data-layer bounded LRU + single-flight cache for batched memo-preview Markdown IR.
 *
 * Cache keys bind workspace identity, mount generation, memo identity, content revision, content
 * kind, and render schema version, and reads/writes re-check admitted authority plus exact preview
 * text before committing or serving entries.
 */
internal class DataMemoPreviewCache(
    private val session: SessionNativeBridge,
    private val readiness: EngineReadinessRepository,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
    private val cacheLock = Any()
    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val coalescedHits = AtomicLong()
    private val batchCalls = AtomicLong()
    private val renderedItems = AtomicLong()
    private val revisionMismatches = AtomicLong()
    private val evictions = AtomicLong()

    private val rawCache =
        object : LinkedHashMap<RawPreviewCacheKey, CachedRawPreview>(PREVIEW_CACHE_MAX_SIZE + 1, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<RawPreviewCacheKey, CachedRawPreview>?,
            ): Boolean {
                val shouldEvict = size > PREVIEW_CACHE_MAX_SIZE
                if (shouldEvict) {
                    evictions.incrementAndGet()
                }
                return shouldEvict
            }
        }

    private val inFlight = HashMap<RawPreviewCacheKey, PendingPreviewEntry>()

    fun stats(): DataMemoPreviewCacheStats {
        val currentSize = synchronized(cacheLock) { rawCache.size }
        return DataMemoPreviewCacheStats(
            hits = hits.get(),
            misses = misses.get(),
            coalescedHits = coalescedHits.get(),
            batchCalls = batchCalls.get(),
            renderedItems = renderedItems.get(),
            revisionMismatches = revisionMismatches.get(),
            evictions = evictions.get(),
            size = currentSize,
        )
    }

    fun peekCached(memo: Memo, schemaVersion: UInt = MarkdownRenderDocument.SCHEMA_VERSION): MarkdownRenderDocument? {
        val revision = memo.contentRevision ?: return null
        val authority = readiness.mount.value.admittedAuthority ?: return null
        val key =
            RawPreviewCacheKey(
                workspaceId = authority.workspaceId,
                generation = authority.generation,
                memoId = memo.id,
                contentRevision = revision,
                contentKind = memo.contentKind,
                schemaVersion = schemaVersion,
            )
        return synchronized(cacheLock) {
            rawCache[key]?.takeIf { entry -> entry.bodyPreview == memo.content }?.document
        }
    }

    suspend fun prefetchLeadingWindow(
        memos: List<Memo>,
        windowSize: Int = DEFAULT_LEADING_PREVIEW_WINDOW,
        isStillValid: () -> Boolean = { true },
    ): List<Memo> {
        if (memos.isEmpty() || !isStillValid()) {
            return memos
        }
        val requests =
            memos
                .asSequence()
                .filter { memo ->
                    memo.contentKind == MemoContentKind.Preview &&
                        memo.contentRevision != null &&
                        memo.previewRenderState is MemoPreviewRenderState.OnDemand
                }.take(windowSize)
                .map { memo ->
                    MemoPreviewRenderRequest(
                        memoId = memo.id,
                        contentRevision = checkNotNull(memo.contentRevision),
                        expectedBodyPreview = memo.content,
                        contentKind = memo.contentKind,
                    )
                }.toList()
        val loaded = if (requests.isEmpty()) emptyMap() else loadPreviews(requests, isStillValid)
        if (!isStillValid()) {
            return memos
        }
        return memos.map { memo ->
            if (memo.contentKind != MemoContentKind.Preview ||
                memo.previewRenderState !is MemoPreviewRenderState.OnDemand
            ) {
                memo
            } else {
                val doc = loaded[memo.id] ?: peekCached(memo)
                if (doc != null) {
                    memo.copy(previewRenderState = MemoPreviewRenderState.Ready(doc))
                } else {
                    memo
                }
            }
        }
    }

    suspend fun loadPreviews(
        requests: List<MemoPreviewRenderRequest>,
        isStillValid: () -> Boolean = { true },
    ): Map<String, MarkdownRenderDocument> {
        if (requests.isEmpty() || !isStillValid()) {
            return emptyMap()
        }
        val authority = readiness.mount.value.admittedAuthority ?: return emptyMap()
        val distinctRequests = requests.distinctBy { it.cacheKey(authority) }
        val results = LinkedHashMap<String, MarkdownRenderDocument>(distinctRequests.size)
        val coalesced = ArrayList<PendingPreviewEntry>()
        val owned = ArrayList<PendingPreviewEntry>()

        synchronized(cacheLock) {
            for (request in distinctRequests) {
                val key = request.cacheKey(authority)
                val cached = rawCache[key]?.takeIf { entry -> entry.bodyPreview == request.expectedBodyPreview }
                if (cached != null) {
                    hits.incrementAndGet()
                    results[request.memoId] = cached.document
                    continue
                }
                misses.incrementAndGet()
                val existing = inFlight[key]
                if (existing != null) {
                    if (existing.request.expectedBodyPreview == request.expectedBodyPreview) {
                        coalescedHits.incrementAndGet()
                        coalesced += existing
                    }
                } else {
                    val entry =
                        PendingPreviewEntry(
                            key = key,
                            request = request,
                            expectedByteLength = request.expectedBodyPreview.encodeToByteArray().size.toULong(),
                            deferred = CompletableDeferred(),
                        )
                    inFlight[key] = entry
                    owned += entry
                }
            }
        }

        if (owned.isNotEmpty()) {
            fetchAndCommitOwnedBatches(
                owned = owned,
                authority = authority,
                isStillValid = isStillValid,
                results = results,
            )
        }

        for (waiter in coalesced) {
            val document = waiter.deferred.await()
            if (document != null) {
                results[waiter.request.memoId] = document
            }
        }
        if (!isStillValid() || readiness.mount.value.admittedAuthority != authority) {
            return emptyMap()
        }
        return results
    }

    private suspend fun fetchAndCommitOwnedBatches(
        owned: List<PendingPreviewEntry>,
        authority: WorkspaceAuthority,
        isStillValid: () -> Boolean,
        results: MutableMap<String, MarkdownRenderDocument>,
    ) {
        var failure: Exception? = null
        try {
            withContext(dispatcherProvider.io) {
                for ((schemaVersion, group) in owned.groupBy { it.request.schemaVersion }) {
                    for (chunk in group.chunked(PREVIEW_BATCH_LIMIT)) {
                        if (!isStillValid() || readiness.mount.value.admittedAuthority != authority) {
                            return@withContext
                        }
                        batchCalls.incrementAndGet()
                        val outcomes =
                            executeMemoPreviewBatchChunk(
                                session = session,
                                requests = chunk.map(PendingPreviewEntry::request),
                                schemaVersion = schemaVersion,
                            )
                        require(outcomes.size == chunk.size) {
                            "preview render batch returned ${outcomes.size} outcomes for ${chunk.size} requests"
                        }
                        if (!isStillValid() || readiness.mount.value.admittedAuthority != authority) {
                            return@withContext
                        }
                        processChunkOutcomes(chunk, outcomes, results)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            settleOwnedEntries(owned, failure)
        }
    }

    private fun processChunkOutcomes(
        chunk: List<PendingPreviewEntry>,
        outcomes: List<SessionMemoPreviewOutcome>,
        results: MutableMap<String, MarkdownRenderDocument>,
    ) {
        for ((entry, outcome) in chunk.zip(outcomes)) {
            when (outcome) {
                is SessionMemoPreviewOutcome.Rendered -> {
                    val exactMatch =
                        outcome.memoId == entry.request.memoId &&
                            outcome.contentRevision == entry.request.contentRevision.toULong() &&
                            outcome.sourceByteLength == entry.expectedByteLength
                    if (exactMatch) {
                        val document = outcome.document.toDomainDocument(sourceByteLength = outcome.sourceByteLength)
                        synchronized(cacheLock) {
                            rawCache[entry.key] = CachedRawPreview(entry.request.expectedBodyPreview, document)
                        }
                        renderedItems.incrementAndGet()
                        results[entry.request.memoId] = document
                        entry.deferred.complete(document)
                    } else {
                        revisionMismatches.incrementAndGet()
                        entry.deferred.complete(null)
                    }
                }

                is SessionMemoPreviewOutcome.RevisionMismatch,
                is SessionMemoPreviewOutcome.NotFound,
                -> {
                    revisionMismatches.incrementAndGet()
                    entry.deferred.complete(null)
                }
            }
        }
    }

    private fun settleOwnedEntries(owned: List<PendingPreviewEntry>, failure: Exception?) {
        synchronized(cacheLock) {
            for (entry in owned) {
                inFlight.remove(entry.key, entry)
            }
        }
        val nonCancellationFailure = failure?.takeUnless { it is CancellationException }
        for (entry in owned) {
            if (!entry.deferred.isCompleted) {
                if (nonCancellationFailure != null) {
                    entry.deferred.completeExceptionally(nonCancellationFailure)
                } else {
                    entry.deferred.complete(null)
                }
            }
        }
    }
}

internal suspend fun fetchUncachedMemoPreviews(
    session: SessionNativeBridge,
    ioDispatcher: CoroutineDispatcher,
    requests: List<MemoPreviewRenderRequest>,
): Map<String, MarkdownRenderDocument> {
    if (requests.isEmpty()) {
        return emptyMap()
    }
    val results = LinkedHashMap<String, MarkdownRenderDocument>(requests.size)
    withContext(ioDispatcher) {
        for ((schemaVersion, group) in requests.groupBy(MemoPreviewRenderRequest::schemaVersion)) {
            for (chunk in group.chunked(PREVIEW_BATCH_LIMIT)) {
                val outcomes = executeMemoPreviewBatchChunk(session, chunk, schemaVersion)
                require(outcomes.size == chunk.size) {
                    "preview render batch outcome count ${outcomes.size} did not match request count ${chunk.size}"
                }
                for ((request, outcome) in chunk.zip(outcomes)) {
                    if (outcome is SessionMemoPreviewOutcome.Rendered &&
                        outcome.memoId == request.memoId &&
                        outcome.contentRevision == request.contentRevision.toULong() &&
                        outcome.sourceByteLength == request.expectedBodyPreview.encodeToByteArray().size.toULong()
                    ) {
                        results[request.memoId] = outcome.document.toDomainDocument(outcome.sourceByteLength)
                    }
                }
            }
        }
    }
    return results
}

private fun executeMemoPreviewBatchChunk(
    session: SessionNativeBridge,
    requests: List<MemoPreviewRenderRequest>,
    schemaVersion: UInt,
): List<SessionMemoPreviewOutcome> = withRenderFailureConversion {
    session.sessionRenderMemoPreviews(
        requests.map { request ->
            SessionMemoPreviewRequest(
                memoId = request.memoId,
                expectedContentRevision = request.contentRevision.toULong(),
            )
        },
        schemaVersion,
    )
}

private fun MemoPreviewRenderRequest.cacheKey(authority: WorkspaceAuthority): RawPreviewCacheKey = RawPreviewCacheKey(
    workspaceId = authority.workspaceId,
    generation = authority.generation,
    memoId = memoId,
    contentRevision = contentRevision,
    contentKind = contentKind,
    schemaVersion = schemaVersion,
)
