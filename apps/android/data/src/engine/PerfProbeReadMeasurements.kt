package com.lomo.data.engine

import android.content.Intent
import com.lomo.data.source.isContentStorageUri
import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.ListingView
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.SessionCreateMemoRequest
import com.lomo.nativebridge.SessionDeleteMemoRequest
import com.lomo.nativebridge.SessionRestoreRequest
import com.lomo.nativebridge.SessionSearchMode
import com.lomo.nativebridge.SessionSearchOutcome
import com.lomo.nativebridge.SessionSearchRequest
import com.lomo.nativebridge.SessionUpdateMemoRequest
import com.lomo.nativebridge.StoreMemoQuery
import com.lomo.nativebridge.StoreMemoSort
import com.lomo.nativebridge.StoreMemoSortField
import com.lomo.nativebridge.StoreSortDirection
import com.lomo.nativebridge.WorkspaceTarget
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

internal suspend fun EnginePerfProbe.paging(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val pages = intent.probeCount("pages", 4)
    val pageSize =
        intent.probeCount("pageSize", DEFAULT_PAGE_SIZE, LIST_PAGE_SIZE)
    val query =
        StoreMemoQuery(
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
    val times = JSONArray()
    var cursor: com.lomo.nativebridge.StorePageCursor? = null
    var fetched = 0
    var completedPages = 0
    var exhausted = false
    // behavior-contract: loop-io-ok: this probe measures successive cursor pages through the production paging API
    while (completedPages < pages) {
        val t0 = System.nanoTime()
        val page = session.queryMemos(query, cursor, pageSize.toUInt(), null, false)
        times.put(elapsedMs(t0))
        fetched += page.items.size
        completedPages += 1
        cursor = page.nextCursor
        if (cursor == null) {
            exhausted = true
            break
        }
    }
    return okPayload(run, "paging").apply {
        put("metrics", JSONObject().put("page_ms", times))
        put(
            "facts",
            JSONObject()
                .put("pages", completedPages)
                .put("itemsFetched", fetched)
                .put("exhausted", exhausted),
        )
    }
}

internal suspend fun EnginePerfProbe.search(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val samples = intent.probeCount("samples", 9)
    val text = intent.getStringExtra("text") ?: SEARCH_TEXT
    val startEpoch = resolveNextSearchEpoch(text, DEFAULT_PAGE_SIZE)
    val times = JSONArray()
    var hits = 0
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
                    pageSize = DEFAULT_PAGE_SIZE.toUInt(),
                ),
            )
        times.put(elapsedMs(t0))
        check(outcome is SessionSearchOutcome.Ready) { "Search probe did not return a ready page: $outcome" }
        hits = outcome.page.items.size
    }
    return okPayload(run, "search").apply {
        put("metrics", JSONObject().put("search_ms", times))
        put("facts", JSONObject().put("query", text).put("lastPageHits", hits))
    }
}

internal suspend fun EnginePerfProbe.history(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val samples = intent.probeCount("samples", 7)
    val n = intent.probeCount("n", DEFAULT_HISTORY_N)
    val pageLimit =
        intent.probeCount("limit", HISTORY_PAGE_LIMIT, LIST_PAGE_SIZE)
    val invocation = ProbeWriteInvocation.newInvocation(run)
    val body = "benchmark owned history probe ${invocation.operationId(0)}"
    val created = session.sessionCreateMemo(
        SessionCreateMemoRequest(
            operationId = invocation.operationId(0),
            relativePath = null,
            timeToken = null,
            content = body,
            expectedDocumentFingerprint = null,
            pinned = false,
            pendingPromotes = emptyList(),
            chronologyEpochMs = null,
            dateFormat = null,
            timestampFormat = null,
        ),
        ReturnedCommitOnly,
    )
    val memoId = created.memoId
    return Closeable {
        val snapshot = checkNotNull(session.getMemo(memoId)) { "history probe memo vanished: $memoId" }
        val cleanup = ProbeWriteInvocation.newInvocation(run)
        session.sessionDeleteMemo(
            SessionDeleteMemoRequest(cleanup.operationId(0), memoId, snapshot.summary.fileFingerprint),
        )
        session.sessionPermanentlyDeleteMemo(SessionRestoreRequest(cleanup.operationId(1), memoId))
    }.use {
        val seedTimes = seedHistory(invocation, memoId, body, created.fileFingerprint, n)
        measureHistory(run, memoId, n, pageLimit, samples).apply {
            getJSONObject("metrics").put("seed_ms", seedTimes)
            getJSONObject("facts").put("revisionsSeeded", seedTimes.length())
        }
    }
}

private fun EnginePerfProbe.measureHistory(
    run: String,
    memoId: String,
    n: Int,
    pageLimit: Int,
    samples: Int,
): JSONObject {
    val times = JSONArray()
    var revisions = 0
    var pages = 0
    var exhausted = false
    repeat(samples) {
        var cursor: String? = null
        var seen = 0
        var pageCount = 0
        var done = false
        val t0 = System.nanoTime()
        while (seen < n && !done) {
            val page =
                session.sessionListHistory(
                    memoId,
                    cursor,
                    minOf(pageLimit, n - seen).toUInt(),
                )
            pageCount += 1
            seen += page.items.size
            cursor = page.nextCursor
            if (cursor == null || page.items.isEmpty()) {
                exhausted = cursor == null
                done = true
            }
        }
        times.put(elapsedMs(t0))
        revisions = seen
        pages = pageCount
    }
    return okPayload(run, "history").apply {
        put("metrics", JSONObject().put("history_ms", times))
        put(
            "facts",
            JSONObject()
                .put("memoId", memoId)
                .put("requestedN", n)
                .put("revisionsListed", revisions)
                .put("pages", pages)
                .put("pageLimit", pageLimit)
                .put("exhausted", exhausted),
        )
    }
}

private fun EnginePerfProbe.seedHistory(
    invocation: ProbeWriteInvocation,
    memoId: String,
    body: String,
    initialFingerprint: String,
    n: Int,
): JSONArray {
    var fingerprint = initialFingerprint
    val times = JSONArray()
    repeat(n) { index ->
        val t0 = System.nanoTime()
        val commit =
            session.sessionUpdateMemo(
                SessionUpdateMemoRequest(
                    operationId = invocation.operationId(index + 1),
                    memoId = memoId,
                    content = "$body\nseed-revision-$index",
                    expectedDocumentFingerprint = fingerprint,
                    pendingPromotes = emptyList(),
                ),
            )
        times.put(elapsedMs(t0))
        fingerprint = commit.fileFingerprint
    }
    return times
}

internal suspend fun EnginePerfProbe.listChildren(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val path =
        intent.getStringExtra("path")
            ?: return errorPayload(run, "listchildren requires --es path")
    val samples = intent.probeCount("samples", 5)
    val pageSize =
        intent.probeCount("pageSize", LIST_PAGE_SIZE, LIST_PAGE_SIZE)
    val location =
        session.activeWorkspaceLocation.value
            ?: return errorPayload(run, "listchildren requires an active workspace")
    // Mirror WorkspaceCandidatePreparation's register path exactly: content URI → SAF
    // grant, otherwise a direct-root grant — then the executor resolves the same
    // BoundDocumentTree the session's own platform actions use.
    val grant =
        if (isContentStorageUri(location.raw)) {
            capabilityRegistry.register(treeUri = location.raw)
        } else {
            capabilityRegistry.registerDirect(rootPath = File(location.raw))
        }
    try {
        val times = JSONArray()
        var itemsListed = 0
        var pages = 0
        repeat(samples) { sample ->
            var cursor: String? = null
            var seen = 0
            var pageCount = 0
            val t0 = System.nanoTime()
            do {
                val outcome =
                    platformAccess.execute(
                        PlatformAction.ListChildren(
                            actionId = "probe-list-$run-$sample-$pageCount",
                            capabilityToken = grant.capabilityToken,
                            target = WorkspaceTarget.Relative(path),
                            cursor = cursor,
                            pageSize = pageSize.toUInt(),
                            view = ListingView.WORKSPACE,
                        ),
                    )
                val listed =
                    when (outcome) {
                        is ActionOutcome.Applied -> outcome.output

                        is ActionOutcome.Failed ->
                            throw com.lomo.nativebridge.EngineError.Failure(outcome.failure)

                        is ActionOutcome.AlreadySatisfied ->
                            error("listChildren cannot be AlreadySatisfied — replay is a protocol violation here")
                    }
                val page =
                    (listed as? PlatformActionOutput.Listed)?.page
                        ?: error("listChildren returned ${listed.javaClass.simpleName}")
                seen += page.items.size
                pageCount += 1
                cursor = page.nextCursor
            } while (cursor != null)
            times.put(elapsedMs(t0))
            itemsListed = seen
            pages = pageCount
        }
        return okPayload(run, "listchildren").apply {
            put("metrics", JSONObject().put("list_ms", times))
            put(
                "facts",
                JSONObject()
                    .put("path", path)
                    .put("itemsListed", itemsListed)
                    .put("pages", pages)
                    .put("pageSize", pageSize)
                    .put(
                        "backend",
                        if (isContentStorageUri(location.raw)) "saf" else "direct",
                    ),
            )
        }
    } finally {
        capabilityRegistry.revoke(grant.capabilityToken)
    }
}

/** A probe epoch at or below the session's active epoch is discarded, so start above it. */
internal fun EnginePerfProbe.resolveNextSearchEpoch(text: String, pageSize: Int): ULong {
    val probe =
        session.sessionSearch(
            SessionSearchRequest(
                filters = EMPTY_FILTERS,
                queryEpoch = 1uL,
                mode = SessionSearchMode.FUZZY,
                text = text,
                cursor = null,
                pageSize = pageSize.toUInt(),
            ),
        )
    return when (probe) {
        is SessionSearchOutcome.Ready -> 2uL
        is SessionSearchOutcome.Discarded -> probe.activeEpoch + 1uL
    }
}
