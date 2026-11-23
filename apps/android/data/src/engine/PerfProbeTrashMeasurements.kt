package com.lomo.data.engine

import android.content.Intent
import com.lomo.nativebridge.SessionCreateMemoRequest
import com.lomo.nativebridge.SessionDeleteMemoRequest
import com.lomo.nativebridge.SessionRestoreRequest
import com.lomo.nativebridge.StoreMemoBatchDelete
import com.lomo.nativebridge.StoreMemoDeleteTarget
import org.json.JSONArray
import org.json.JSONObject

/*
 * Behavior Contract: time trash/restore/batch-empty through the production session.
 * Given a ready corpus, when a sample runs, then two newly created probe identities alone are
 * trashed and batch-deleted, and one is restored/retrashed first. Every target carries its latest
 * owner-issued source/version/fingerprint. Existing trash is never selected for destruction.
 * TDD: installed debug probe rejected trash_cycle; real-device RED/GREEN evidence is archived.
 * Excludes: sweeping unrelated media and emptying user-owned trash.
 */
internal suspend fun EnginePerfProbe.trashCycle(intent: Intent, run: String): JSONObject {
    val samples = intent.probeCount("samples", 5, MAX_MUTATION_PROBE_SAMPLES)
    requireReadySession()
    val invocation = ProbeWriteInvocation.newInvocation(run)
    val times = ProbeTrashTimes()
    val emptyTimes = JSONArray()
    repeat(samples) { sample ->
        val base = sample * TRASH_OPERATION_STRIDE
        val targets = listOf(
            seedTrashTarget(invocation, base, true, times),
            seedTrashTarget(invocation, base + TRASH_SECOND_MEMO_OFFSET, false, times),
        ).sortedBy(StoreMemoDeleteTarget::memoId)
        val start = System.nanoTime()
        val commit = session.sessionPermanentlyDeleteMany(
            StoreMemoBatchDelete(invocation.operationId(base + TRASH_BATCH_OFFSET), targets),
        )
        emptyTimes.put(elapsedMs(start))
        check(commit.deleted.map { it.memoId }.sorted() == targets.map { it.memoId }) {
            "Trash probe batch did not delete exactly its owned targets"
        }
        check(targets.all { session.getMemo(it.memoId) == null }) { "Deleted probe memo remains queryable" }
    }
    return okPayload(run, "trash_cycle").put(
        "metrics",
        JSONObject().put("trash_ms", times.trash).put("restore_ms", times.restore)
            .put("empty_trash_batch_ms", emptyTimes),
    ).put(
        "facts",
        JSONObject().put("samples", samples).put("batchSize", TRASH_BATCH_SIZE)
            .put("deletedOwnedMemos", samples * TRASH_BATCH_SIZE).put("restoredOwnedMemos", samples),
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
): StoreMemoDeleteTarget {
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
        "Probe trash memo vanished before batch deletion"
    }.summary
    check(summary.isTrashed) { "Probe memo was not admitted to trash" }
    return StoreMemoDeleteTarget(summary.memoId, summary.sourcePath, summary.contentRevision, summary.fileFingerprint)
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
