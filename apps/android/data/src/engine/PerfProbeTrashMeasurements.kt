package com.lomo.data.engine

import android.content.Intent
import com.lomo.nativebridge.EmptyTrashCancellation
import com.lomo.nativebridge.SessionCreateMemoRequest
import com.lomo.nativebridge.SessionDeleteMemoRequest
import com.lomo.nativebridge.SessionRestoreRequest
import com.lomo.nativebridge.StoreMemoQuery
import com.lomo.nativebridge.StoreMemoSort
import com.lomo.nativebridge.StoreMemoSortField
import com.lomo.nativebridge.StoreSortDirection
import org.json.JSONArray
import org.json.JSONObject

/*
 * Behavior Contract: time trash/restore/empty-trash through the production session.
 * Given a ready corpus, when a sample runs, then two newly created probe identities alone are
 * trashed and cleared via session_empty_trash, and one is restored/retrashed first. Existing trash
 * is never present when empty_trash runs; any unexpected resident fails closed before deleting.
 * TDD: installed debug probe rejected trash_cycle; real-device RED/GREEN evidence is archived.
 * Excludes: sweeping unrelated media and emptying user-owned trash.
 */
internal suspend fun EnginePerfProbe.trashCycle(intent: Intent, run: String): JSONObject {
    val samples = intent.probeCount("samples", 5, MAX_MUTATION_PROBE_SAMPLES)
    requireReadySession()
    val invocation = ProbeWriteInvocation.newInvocation(run)
    val times = ProbeTrashTimes()
    val emptyTimes = JSONArray()
    var totalBatches = 0
    val trashQuery = StoreMemoQuery(
        searchText = null,
        filters = EMPTY_FILTERS.copy(includeTrash = true, trashOnly = true),
        sort =
        StoreMemoSort(
            seed = 0L,
            timeZone = "UTC",
            field = StoreMemoSortField.CREATED_AT,
            direction = StoreSortDirection.DESCENDING,
        ),
        boundary = null,
    )
    repeat(samples) { sample ->
        val base = sample * TRASH_OPERATION_STRIDE
        val targetIds = listOf(
            seedTrashTarget(invocation, base, true, times),
            seedTrashTarget(invocation, base + TRASH_SECOND_MEMO_OFFSET, false, times),
        ).sorted()
        val trashPage = session.queryMemos(trashQuery, null, (targetIds.size + 1).toUInt(), null, false)
        check(trashPage.nextCursor == null && trashPage.items.map { it.memoId }.sorted() == targetIds) {
            "Trash probe requires trash lane to contain only owned targets"
        }
        val start = System.nanoTime()
        val result = session.sessionEmptyTrash(
            invocation.operationId(base + TRASH_BATCH_OFFSET),
            EmptyTrashCancellation { false },
        )
        emptyTimes.put(elapsedMs(start))
        check(!result.cancelled) { "Trash probe empty_trash must not report cancelled" }
        check(result.failure == null) { "Trash probe empty_trash failed: ${result.failure?.code}" }
        check(result.removed.toInt() == targetIds.size) {
            "Trash probe empty_trash removed ${result.removed} != ${targetIds.size}"
        }
        check(result.batches.flatMap { it.deleted }.map { it.memoId }.sorted() == targetIds) {
            "Trash probe batch did not delete exactly its owned targets"
        }
        check(targetIds.all { session.getMemo(it) == null }) { "Deleted probe memo remains queryable" }
        totalBatches += result.batches.size
    }
    return okPayload(run, "trash_cycle").put(
        "metrics",
        JSONObject().put("trash_ms", times.trash).put("restore_ms", times.restore)
            .put("empty_trash_batch_ms", emptyTimes),
    ).put(
        "facts",
        JSONObject().put("samples", samples).put("batchSize", TRASH_BATCH_SIZE)
            .put("ffiCallsPerSample", 1)
            .put("batches", totalBatches)
            .put("removed", samples * TRASH_BATCH_SIZE)
            .put("deletedOwnedMemos", samples * TRASH_BATCH_SIZE)
            .put("restoredOwnedMemos", samples),
    )
}

private class ProbeTrashTimes {
    val trash = JSONArray()
    val restore = JSONArray()
}

private fun EnginePerfProbe.seedTrashTarget(
    invocation: ProbeWriteInvocation,
    base: Int,
    restore: Boolean,
    times: ProbeTrashTimes,
): String {
    val created = session.sessionCreateMemo(
        SessionCreateMemoRequest(
            operationId = invocation.operationId(base),
            relativePath = null,
            timeToken = null,
            content = "benchmark owned trash probe ${invocation.operationId(base)}",
            expectedDocumentFingerprint = null,
            pinned = false,
            pendingPromotes = emptyList(),
            chronologyEpochMs = null,
            dateFormat = null,
            timestampFormat = null,
        ),
        ReturnedCommitOnly,
    )
    var start = System.nanoTime()
    session.sessionDeleteMemo(
        SessionDeleteMemoRequest(invocation.operationId(base + 1), created.memoId, created.fileFingerprint),
    )
    times.trash.put(elapsedMs(start))
    if (restore) {
        start = System.nanoTime()
        val restored = session.sessionRestoreMemo(
            SessionRestoreRequest(invocation.operationId(base + 2), created.memoId),
        )
        times.restore.put(elapsedMs(start))
        check(!requireNotNull(session.getMemo(created.memoId)).summary.isTrashed) { "Probe restore left memo in trash" }
        start = System.nanoTime()
        session.sessionDeleteMemo(
            SessionDeleteMemoRequest(
                invocation.operationId(base + TRASH_REDELETE_OFFSET),
                created.memoId,
                restored.fileFingerprint,
            ),
        )
        times.trash.put(elapsedMs(start))
    }
    val summary = requireNotNull(session.getMemo(created.memoId)) {
        "Probe trash memo vanished before empty_trash"
    }.summary
    check(summary.isTrashed) { "Probe memo was not admitted to trash" }
    return summary.memoId
}

private const val TRASH_BATCH_SIZE = 2
private const val TRASH_OPERATION_STRIDE = 9
private const val TRASH_SECOND_MEMO_OFFSET = 4
private const val TRASH_BATCH_OFFSET = 8
private const val TRASH_REDELETE_OFFSET = 3
internal const val MAX_MUTATION_PROBE_SAMPLES = 10

/** The trash probe times only delete/restore; the seed create's publications are not measured. */
private object ReturnedCommitOnly : com.lomo.nativebridge.MemoCreateObserver {
    override fun accepted(publication: com.lomo.nativebridge.StoreMemoCommit) = Unit

    override fun withdrawn(publication: com.lomo.nativebridge.StoreMemoCommit) = Unit
}
