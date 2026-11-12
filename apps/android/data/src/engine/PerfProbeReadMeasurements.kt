package com.lomo.data.engine

import android.content.Intent
import com.lomo.data.source.isContentStorageUri
import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionOutput
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
    val times = JSONArray()
    var hits = 0
    repeat(samples) { index ->
        val t0 = System.nanoTime()
        val outcome =
            session.sessionSearch(
                SessionSearchRequest(
                    filters = EMPTY_FILTERS,
                    queryEpoch = (index + 1).toULong(),
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
    val memoId =
        firstMemoId()
            ?: return errorPayload(run, "history probe requires at least one memo")
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

internal suspend fun EnginePerfProbe.historySeed(intent: Intent, run: String): JSONObject {
    requireReadySession()
    val n = intent.probeCount("n", DEFAULT_HISTORY_N)
    val memoId =
        firstMemoId()
            ?: return errorPayload(run, "history_seed requires at least one memo")
    val snapshot =
        session.getMemo(memoId)
            ?: return errorPayload(run, "history_seed memo vanished: $memoId")
    // Operation ids are idempotency keys fenced by the durable intents journal in the
    // session control root — that journal survives corpus re-staging, so an opId reused
    // across probe invocations with a different payload is (correctly) an
    // `operation_payload_mismatch`. A per-invocation nonce keeps each run's keys unique.
    val invocation = ProbeWriteInvocation.newInvocation(run)
    var fingerprint = snapshot.summary.fileFingerprint
    val times = JSONArray()
    var contentRevision = 0uL
    repeat(n) { index ->
        val t0 = System.nanoTime()
        val commit =
            session.sessionUpdateMemo(
                SessionUpdateMemoRequest(
                    operationId = invocation.operationId(index),
                    memoId = memoId,
                    content = "${snapshot.body}\nseed-revision-$index",
                    expectedDocumentFingerprint = fingerprint,
                    pendingPromotes = emptyList(),
                ),
            )
        times.put(elapsedMs(t0))
        fingerprint = commit.fileFingerprint
        contentRevision = commit.contentRevision
    }
    return okPayload(run, "history_seed").apply {
        put("metrics", JSONObject().put("seed_ms", times))
        put(
            "facts",
            JSONObject()
                .put("memoId", memoId)
                .put("revisionsSeeded", n)
                .put("finalContentRevision", contentRevision.toLong()),
        )
    }
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
