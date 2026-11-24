package com.lomo.data.engine

import android.content.Intent
import android.os.Debug
import com.lomo.data.engine.store.DEFAULT_STORE_PAGING_SIZE
import com.lomo.data.engine.store.toDomainMemo
import com.lomo.data.engine.store.toStoreSummary
import com.lomo.domain.model.MemoContentKind
import com.lomo.nativebridge.SessionSearchMode
import com.lomo.nativebridge.SessionSearchOutcome
import com.lomo.nativebridge.SessionSearchRequest
import com.lomo.nativebridge.StoreMemoPage
import com.lomo.nativebridge.StoreMemoQuery
import com.lomo.nativebridge.StoreMemoSort
import com.lomo.nativebridge.StoreMemoSortField
import com.lomo.nativebridge.StoreSortDirection
import org.json.JSONArray
import org.json.JSONObject

/*
 * Production-shaped engine probes: the main feed's page size, the search first page, the card
 * preview render fan-out, storage media classification and the gallery query. Each probe keeps
 * the returned row counts and a cross-sample output check next to its timings so a sample that
 * measured a different workload fails instead of joining the distribution.
 */

/** First page plus one continuation at the production page size, with the DTO → domain mapping timed apart. */
internal suspend fun EnginePerfProbe.pagingProduction(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val samples = intent.probeCount("samples", DEFAULT_PROD_SAMPLES)
    val pageSize = intent.probeCount("pageSize", DEFAULT_STORE_PAGING_SIZE, LIST_PAGE_SIZE)
    val query = defaultFeedQuery()
    val firstTimes = JSONArray()
    val nextTimes = JSONArray()
    val mapTimes = JSONArray()
    var expected: ProductionPageFacts? = null
    val allocatedBefore = allocatedBytes()
    repeat(samples) {
        val t0 = System.nanoTime()
        val first = session.queryMemos(query, null, pageSize.toUInt(), null, false)
        firstTimes.put(elapsedMs(t0))
        val cursor = checkNotNull(first.nextCursor) { "paging_prod requires a continuation cursor" }
        val t1 = System.nanoTime()
        val next = session.queryMemos(query, cursor, pageSize.toUInt(), null, false)
        nextTimes.put(elapsedMs(t1))
        val t2 = System.nanoTime()
        val mapped =
            first.items.map { item ->
                val summary = item.toStoreSummary()
                summary.toDomainMemo(summary.bodyPreview, MemoContentKind.Preview)
            }
        mapTimes.put(elapsedMs(t2))
        check(mapped.size == first.items.size) { "paging_prod mapping dropped rows" }
        val facts = ProductionPageFacts.of(first, next)
        check(facts.firstRows > 0) { "paging_prod expected a non-empty first page" }
        check(expected == null || expected == facts) { "paging_prod output drifted across samples" }
        expected = facts
    }
    val allocated = allocatedBytes() - allocatedBefore
    val facts = checkNotNull(expected) { "paging_prod produced no samples" }
    return okPayload(run, "paging_prod").apply {
        put(
            "metrics",
            JSONObject()
                .put("paging_first_${pageSize}_ms", firstTimes)
                .put("paging_next_${pageSize}_ms", nextTimes)
                .put("paging_map_${pageSize}_ms", mapTimes),
        )
        put(
            "facts",
            JSONObject()
                .put("samples", samples)
                .put("pageSize", pageSize)
                .put("ffiCallsPerSample", 2)
                .put("firstPageRows", facts.firstRows)
                .put("nextPageRows", facts.nextRows)
                .put("firstPageTagCount", facts.firstTags)
                .put("firstPageImageCount", facts.firstImages)
                .put("firstPagePreviewUtf8Bytes", facts.firstPreviewBytes)
                .put("nextPagePreviewUtf8Bytes", facts.nextPreviewBytes)
                .put("allocatedBytesPerSample", allocated / samples),
        )
    }
}

private data class ProductionPageFacts(
    val firstIds: List<String>,
    val nextIds: List<String>,
    val firstTags: Int,
    val firstImages: Int,
    val firstPreviewBytes: Long,
    val nextPreviewBytes: Long,
) {
    val firstRows: Int get() = firstIds.size
    val nextRows: Int get() = nextIds.size

    companion object {
        fun of(first: StoreMemoPage, next: StoreMemoPage) = ProductionPageFacts(
            firstIds = first.items.map { it.memoId },
            nextIds = next.items.map { it.memoId },
            firstTags = first.items.sumOf { it.tags.size },
            firstImages = first.items.sumOf { it.imageUrls.size },
            firstPreviewBytes = first.items.sumOf { it.bodyPreview.utf8Size() },
            nextPreviewBytes = next.items.sumOf { it.bodyPreview.utf8Size() },
        )
    }
}

internal suspend fun EnginePerfProbe.searchProduction(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val samples = intent.probeCount("samples", DEFAULT_PROD_SAMPLES)
    val pageSize = intent.probeCount("pageSize", PRODUCTION_PAGE_SIZE, LIST_PAGE_SIZE)
    val text = intent.getStringExtra("text") ?: SEARCH_TEXT
    val startEpoch = resolveNextSearchEpoch(text, pageSize)
    val times = JSONArray()
    var expectedIds: List<String>? = null
    var previewBytes = 0L
    val allocatedBefore = allocatedBytes()
    repeat(samples) { index ->
        val t0 = System.nanoTime()
        val outcome =
            session.sessionSearch(
                SessionSearchRequest(
                    filters = EMPTY_FILTERS,
                    queryEpoch = startEpoch + index.toULong(),
                    mode = SessionSearchMode.FUZZY,
                    text = text,
                    cursor = null,
                    pageSize = pageSize.toUInt(),
                ),
            )
        times.put(elapsedMs(t0))
        check(outcome is SessionSearchOutcome.Ready) { "search_prod did not return a ready page: $outcome" }
        val ids = outcome.page.items.map { it.summary.memoId }
        check(ids.isNotEmpty()) { "search_prod expected hits for '$text'" }
        check(expectedIds == null || expectedIds == ids) { "search_prod output drifted across samples" }
        expectedIds = ids
        previewBytes = outcome.page.items.sumOf { it.summary.bodyPreview.utf8Size() }
    }
    val allocated = allocatedBytes() - allocatedBefore
    return okPayload(run, "search_prod").apply {
        put("metrics", JSONObject().put("search_first_${pageSize}_ms", times))
        put(
            "facts",
            JSONObject()
                .put("samples", samples)
                .put("pageSize", pageSize)
                .put("query", text)
                .put("ffiCallsPerSample", 1)
                .put("hits", expectedIds.orEmpty().size)
                .put("previewUtf8Bytes", previewBytes)
                .put("allocatedBytesPerSample", allocated / samples),
        )
    }
}

/**
 * Every card preview of one production page rendered through batched `sessionRenderMemoPreviews`.
 *
 * Also measures smaller batch sizes (2 and 4) for diagnostic comparison in `facts` while keeping
 * `preview_render_20_ms` tied to the configured production batch size (8).
 */
internal suspend fun EnginePerfProbe.previewRender(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val samples = intent.probeCount("samples", DEFAULT_PROD_SAMPLES)
    val count = intent.probeCount("count", DEFAULT_LEADING_PREVIEW_WINDOW, LIST_PAGE_SIZE)
    val page = session.queryMemos(defaultFeedQuery(), null, count.toUInt(), null, false)
    check(page.items.isNotEmpty()) { "preview_render requires populated memos" }
    val requests =
        page.items.map { summary ->
            com.lomo.nativebridge.SessionMemoPreviewRequest(
                memoId = summary.memoId,
                expectedContentRevision = summary.contentRevision,
            )
        }
    val expectedLengths = page.items.map { summary -> summary.memoId to summary.bodyPreview.utf8Size().toULong() }
    val times = JSONArray()
    var expected: PreviewRenderFacts? = null
    val allocatedBefore = allocatedBytes()
    repeat(samples) {
        val t0 = System.nanoTime()
        val facts = runPreviewRenderBatch(requests, expectedLengths, PREVIEW_RENDER_FFI_BATCH_SIZE)
        times.put(elapsedMs(t0))
        check(expected == null || expected == facts) { "preview_render output drifted across samples" }
        expected = facts
    }
    val allocated = allocatedBytes() - allocatedBefore
    val facts = checkNotNull(expected) { "preview_render produced no samples" }
    val batch8Calls = requests.chunked(PREVIEW_RENDER_FFI_BATCH_SIZE).size
    val batch2P50Ms =
        measureBatchP50(requests, expectedLengths, batchSize = SMALL_PREVIEW_RENDER_BATCH, samples = samples)
    val batch4P50Ms =
        measureBatchP50(requests, expectedLengths, batchSize = MEDIUM_PREVIEW_RENDER_BATCH, samples = samples)
    val floorTimes = JSONArray()
    repeat(samples) {
        val t0 = System.nanoTime()
        repeat(batch8Calls) { session.renderMarkdown(RENDER_FLOOR_BODY) }
        floorTimes.put(elapsedMs(t0))
    }
    return okPayload(run, "preview_render").apply {
        put(
            "metrics",
            JSONObject()
                .put("preview_render_${count}_ms", times)
                .put("preview_render_call_floor_${count}_ms", floorTimes),
        )
        put(
            "facts",
            JSONObject()
                .put("samples", samples)
                .put("memoCount", requests.size)
                .put("ffiCallsPerSample", batch8Calls)
                .put("ffiBatchSize", PREVIEW_RENDER_FFI_BATCH_SIZE)
                .put("sourceBytesSent", 0L)
                .put("batch2Calls", requests.chunked(SMALL_PREVIEW_RENDER_BATCH).size)
                .put("batch4Calls", requests.chunked(MEDIUM_PREVIEW_RENDER_BATCH).size)
                .put("batch2P50Ms", batch2P50Ms)
                .put("batch4P50Ms", batch4P50Ms)
                .put("nodeCount", facts.nodes)
                .put("attachments", facts.attachments)
                .put("plainTextLength", facts.plainTextLength)
                .put("allocatedBytesPerSample", allocated / samples),
        )
    }
}

private fun EnginePerfProbe.runPreviewRenderBatch(
    requests: List<com.lomo.nativebridge.SessionMemoPreviewRequest>,
    expectedLengths: List<Pair<String, ULong>>,
    chunkSize: Int,
): PreviewRenderFacts {
    var nodes = 0
    var attachments = 0
    var plainTextLength = 0L
    var offset = 0
    for (chunk in requests.chunked(chunkSize)) {
        val outcomes = session.sessionRenderMemoPreviews(chunk, PREVIEW_RENDER_SCHEMA_V1)
        check(outcomes.size == chunk.size) { "preview_render batch size mismatch" }
        outcomes.forEach { outcome ->
            val rendered = outcome as? com.lomo.nativebridge.SessionMemoPreviewOutcome.Rendered
                ?: error("preview_render expected Rendered outcome, got $outcome")
            val (expectedId, expectedBytes) = expectedLengths[offset++]
            check(rendered.memoId == expectedId && rendered.sourceByteLength == expectedBytes) {
                "preview_render outcome mismatch for $expectedId"
            }
            nodes += rendered.document.nodeCount.toInt()
            attachments += rendered.document.attachments.size
            plainTextLength += rendered.document.plainText.length
        }
    }
    return PreviewRenderFacts(nodes, attachments, plainTextLength)
}

private fun EnginePerfProbe.measureBatchP50(
    requests: List<com.lomo.nativebridge.SessionMemoPreviewRequest>,
    expectedLengths: List<Pair<String, ULong>>,
    batchSize: Int,
    samples: Int,
): Double {
    val timings = ArrayList<Double>(samples)
    repeat(samples) {
        val t0 = System.nanoTime()
        runPreviewRenderBatch(requests, expectedLengths, batchSize)
        timings += elapsedMs(t0)
    }
    timings.sort()
    return timings[timings.size / 2]
}

private const val RENDER_FLOOR_BODY = "x"
private const val SMALL_PREVIEW_RENDER_BATCH = 2
private const val MEDIUM_PREVIEW_RENDER_BATCH = 4
private const val PREVIEW_RENDER_FFI_BATCH_SIZE = 8
private const val PREVIEW_RENDER_SCHEMA_V1 = 1u

private data class PreviewRenderFacts(val nodes: Int, val attachments: Int, val plainTextLength: Long)

/** The gallery's dedicated cursor page query at the production page size. */
internal suspend fun EnginePerfProbe.galleryPaging(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val samples = intent.probeCount("samples", DEFAULT_PROD_SAMPLES)
    val pageSize = intent.probeCount("pageSize", DEFAULT_STORE_PAGING_SIZE, LIST_PAGE_SIZE)
    val times = JSONArray()
    var expectedIds: List<String>? = null
    var imageTargets = 0
    var imageRows = 0
    repeat(samples) {
        val t0 = System.nanoTime()
        val page = session.queryGalleryPage(null, pageSize.toUInt(), null, false)
        times.put(elapsedMs(t0))
        val ids = page.items.map { item -> item.memoId }
        check(ids.isNotEmpty()) { "gallery_paging expected image-bearing memos" }
        check(expectedIds == null || expectedIds == ids) { "gallery_paging output drifted across samples" }
        expectedIds = ids
        imageTargets = page.items.sumOf { item -> item.imageUrls.size }
        imageRows = page.items.count { item -> item.imageUrls.isNotEmpty() }
    }
    return okPayload(run, "gallery_paging").apply {
        put("metrics", JSONObject().put("gallery_paging_${pageSize}_ms", times))
        put(
            "facts",
            JSONObject()
                .put("samples", samples)
                .put("pageSize", pageSize)
                .put("ffiCallsPerSample", 1)
                .put("itemsFetched", expectedIds.orEmpty().size)
                .put("itemsWithImageTargets", imageRows)
                .put("imageTargetCount", imageTargets)
                .put("previewUtf8Bytes", 0L),
        )
    }
}

private fun defaultFeedQuery(): StoreMemoQuery = StoreMemoQuery(
    searchText = null,
    filters = EMPTY_FILTERS,
    sort =
    StoreMemoSort(
        seed = 0L,
        timeZone = "UTC",
        field = StoreMemoSortField.CREATED_AT,
        direction = StoreSortDirection.DESCENDING,
    ),
    boundary = null,
)

internal fun String.utf8Size(): Long = encodeToByteArray().size.toLong()

/** Process-wide ART allocation counter; a per-sample delta bounds what the measured calls allocated. */
private fun allocatedBytes(): Long = requireNotNull(Debug.getRuntimeStat("art.gc.bytes-allocated").toLongOrNull()) {
    "ART runtime does not report art.gc.bytes-allocated"
}
