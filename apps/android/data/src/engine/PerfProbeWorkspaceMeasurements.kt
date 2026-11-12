package com.lomo.data.engine

import android.content.Intent
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.StorageLocation
import com.lomo.nativebridge.StoreMemoQuery
import com.lomo.nativebridge.StoreMemoSort
import com.lomo.nativebridge.StoreMemoSortField
import com.lomo.nativebridge.StoreSortDirection
import org.json.JSONArray
import org.json.JSONObject

/*
 * Behavior Contract: measure real workspace activation/reconcile, then validate its projection.
 * Given two distinct corpus roots, activation uses the production journaled switch and rejects
 * an already-active root. Reconcile requires the named active root. Both require Ready, exact
 * memo/task cardinality, and the expected corpus marker in a hydrated memo before publishing.
 * First-page latency is separate from activation/reconcile; correctness reads are unmeasured.
 * TDD: workspace-probe-red.json reports unknown workspace_activate on the prior installed APK;
 * workspace-cli-red.log rejects the new plane. Device GREEN covers both backends and bad inputs.
 * Excludes: DocumentsUI selection time, Compose rendering and physical-device release truth.
 */
internal suspend fun EnginePerfProbe.measureWorkspace(intent: Intent, run: String, activate: Boolean): JSONObject {
    val location = StorageLocation(requireNotNull(intent.getStringExtra("location")))
    require(location.raw.isNotBlank()) { "workspace probe requires a location" }
    val expectedMemos = intent.probeCount("expectedMemos", WORKSPACE_MEMOS)
    val expectedTasks = intent.probeCount("expectedTasks", WORKSPACE_TASKS)
    val marker = requireNotNull(intent.getStringExtra("marker")) { "workspace probe requires marker" }
    require(marker.isNotBlank()) { "workspace marker must not be blank" }
    val initial = session.requestEngineStart()
    check(initial is EngineReadiness.Ready || (activate && initial is EngineReadiness.AwaitingWorkspaceSelection)) {
        "workspace probe requires Ready or a first workspace selection: $initial"
    }
    val previous = session.mount.value.location
    require(if (activate) previous != location else previous == location) {
        "workspace ${if (activate) "activation requires a different" else "reconcile requires the active"} root"
    }
    val start = System.nanoTime()
    if (activate) {
        switchRoot.updateRootLocation(location)
    } else {
        session.startRebuild(REBUILD_BATCH_SIZE.toUInt())
    }
    val operationMs = elapsedMs(start)
    val mount = session.mount.value
    check(mount.location == location && mount.admitsProjectionReads) {
        "workspace probe did not commit the requested Ready root: $mount"
    }
    val query = StoreMemoQuery(
        searchText = null,
        filters = EMPTY_FILTERS,
        sort = StoreMemoSort(StoreMemoSortField.CREATED_AT, StoreSortDirection.DESCENDING, 0L, "UTC"),
        boundary = null,
    )
    val firstRead = System.nanoTime()
    var page = session.queryMemos(query, null, DEFAULT_PAGE_SIZE.toUInt(), null, false)
    val firstPageMs = elapsedMs(firstRead)
    val first = checkNotNull(page.items.firstOrNull()) { "workspace projection is empty" }
    val memo = checkNotNull(session.getMemo(first.memoId)) { "workspace first memo vanished" }
    check(marker in memo.body) { "workspace projection contains the wrong corpus" }
    var memoCount = page.items.size
    // behavior-contract: loop-io-ok: validate the complete bounded corpus outside the timed sample
    while (page.nextCursor != null) {
        check(memoCount < expectedMemos) { "workspace projection exceeds expected memo count" }
        page = session.queryMemos(query, page.nextCursor, DEFAULT_PAGE_SIZE.toUInt(), null, false)
        check(page.items.isNotEmpty()) { "workspace cursor did not advance" }
        memoCount += page.items.size
    }
    val taskCount = session.sessionListTasks().size
    check(memoCount == expectedMemos && taskCount == expectedTasks) {
        "workspace projection mismatch: memos=$memoCount/$expectedMemos tasks=$taskCount/$expectedTasks"
    }
    return okPayload(run, if (activate) "workspace_activate" else "workspace_reconcile").put(
        "metrics",
        JSONObject().put("operation_ms", JSONArray().put(operationMs))
            .put("first_page_ms", JSONArray().put(firstPageMs)),
    ).put(
        "facts",
        JSONObject().put("location", location.raw).put("previousLocation", previous?.raw)
            .put("memos", memoCount).put("tasks", taskCount).put("marker", marker),
    )
}

private const val WORKSPACE_MEMOS = 240
private const val WORKSPACE_TASKS = 80
