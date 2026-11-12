package com.lomo.data.engine

import android.content.Intent
import com.lomo.nativebridge.SessionCivilDate
import com.lomo.nativebridge.SessionStatisticsSnapshot
import com.lomo.nativebridge.StoreMemoQuery
import com.lomo.nativebridge.StoreMemoSort
import com.lomo.nativebridge.StoreMemoSortField
import com.lomo.nativebridge.StoreSortDirection
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/*
 * Behavior Contract: measure production tasks/statistics and bounded review/trash summary reads.
 * Given an admitted corpus and an explicit UTC as-of instant, when samples run, then every
 * metric comes from that session and reports actual row counts. The task workload must contain
 * rows; an empty task collection is rejected rather than reported as task performance.
 * Invalid counts/time fail before measuring. Review candidates are summary-only, matching
 * the production repository's query.
 * TDD: installed debug probe rejected views as unknown; device E6 RED/GREEN evidence is archived.
 * Excludes: Compose layout, review selection policy and full-body hydration of summary queries.
 */
internal suspend fun EnginePerfProbe.views(intent: Intent, run: String): JSONObject {
    val samples = intent.probeCount("samples", 5)
    val epoch = requireNotNull(intent.getStringExtra("asOfEpochMs")?.toLongOrNull()) {
        "views requires asOfEpochMs"
    }
    val date = Instant.ofEpochMilli(epoch).atZone(ZoneOffset.UTC).toLocalDate()
    require(date.year in 1..MAX_CIVIL_YEAR) { "views as-of date is outside the civil date range" }
    val snapshot = SessionStatisticsSnapshot(
        zone = "UTC",
        asOf = SessionCivilDate(date.year, date.monthValue.toUShort(), date.dayOfMonth.toUShort()),
    )
    requireReadySession()
    val query = StoreMemoQuery(
        searchText = null,
        filters = EMPTY_FILTERS,
        sort = StoreMemoSort(StoreMemoSortField.CREATED_AT, StoreSortDirection.DESCENDING, 0L, "UTC"),
        boundary = null,
    )
    val tasks = JSONArray()
    val statistics = JSONArray()
    val review = JSONArray()
    val trash = JSONArray()
    val facts = JSONObject().put("asOf", date.toString()).put("zone", snapshot.zone)
    repeat(samples) {
        var start = System.nanoTime()
        val rows = session.sessionListTasks()
        check(rows.isNotEmpty()) { "views requires task-bearing corpus; task collection is empty" }
        tasks.put(elapsedMs(start))
        facts.put("taskRows", rows.size)
        start = System.nanoTime()
        val stats = session.sessionStatistics(snapshot)
        statistics.put(elapsedMs(start))
        facts.put("totalMemos", stats.totalMemos.toLong())
        start = System.nanoTime()
        val candidates = session.queryMemos(query, null, DEFAULT_PAGE_SIZE.toUInt(), null, false)
        review.put(elapsedMs(start))
        facts.put("reviewCandidateSummaries", candidates.items.size)
        start = System.nanoTime()
        val deleted = session.queryMemos(
            query.copy(filters = EMPTY_FILTERS.copy(includeTrash = true, trashOnly = true)),
            null,
            DEFAULT_PAGE_SIZE.toUInt(),
            null,
            false,
        )
        trash.put(elapsedMs(start))
        facts.put("trashSummaries", deleted.items.size)
    }
    return okPayload(run, "views").put("facts", facts).put(
        "metrics",
        JSONObject().put("tasks_ms", tasks).put("statistics_ms", statistics)
            .put("review_candidates_ms", review).put("trash_query_ms", trash),
    )
}

private const val MAX_CIVIL_YEAR = 9999
