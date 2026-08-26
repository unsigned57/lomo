package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: StoreInvalidationBus.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: publish Rust store commits as one monotonic, scoped projection-version stream.
 *
 * Scenarios:
 * - Given memo-list and trash consumers, when a contiguous memo-list commit is published, then
 *   only the memo-list consumer invalidates and the exact Rust revision/sequence is observable.
 * - Given an already-published commit, when its idempotent replay or an older commit arrives, then
 *   no publication regresses and a newly registered consumer remains valid.
 * - Given a missing event sequence, when the later commit arrives, then its scopes are promoted to
 *   Full and every registered projection consumer invalidates.
 * - Given a completed rebuild, when its higher high-water revision is published, then consumers
 *   full-invalidate once; a stale rebuild result is ignored.
 *
 * Observable outcomes:
 * - PagingSource invalid state and StoreProjectionPublication revision, sequence, and scopes.
 *
 * TDD proof:
 * - RED on 2026-08-09: StoreInvalidationBus exposed only an untyped bump counter, so commit order,
 *   replay identity, lost-event gaps, rebuild high-water revisions, and scoped invalidation were
 *   impossible to represent.
 *
 * Excludes:
 * - Store query contents, JNI transport, Compose rendering, and native event subscription.
 */

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.engine.store.StoreInvalidationScope
import com.lomo.data.engine.store.StoreMemoCommit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private class ProjectionPagingSource : PagingSource<Int, String>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, String> =
        LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

    override fun getRefreshKey(state: PagingState<Int, String>): Int? = null
}

private fun commit(
    revision: Long,
    sequence: Long,
    scopes: List<StoreInvalidationScope>,
    idempotentReplay: Boolean = false,
): StoreMemoCommit =
    StoreMemoCommit(
        operationId = "op-$revision-$sequence",
        memoId = "memo-1",
        coreRevision = revision,
        eventSequence = sequence,
        contentRevision = revision,
        fileFingerprint = "fp-$revision",
        scopes = scopes,
        idempotentReplay = idempotentReplay,
    )

class StoreInvalidationBusTest : FunSpec({
    test("contiguous commit invalidates only consumers selected by Rust scopes") {
        val bus = StoreInvalidationBus()
        val memoList = ProjectionPagingSource()
        val trash = ProjectionPagingSource()
        bus.register(memoList, setOf(StoreInvalidationScope.MemoList))
        bus.register(trash, setOf(StoreInvalidationScope.Trash))

        bus.publish(
            commit(
                revision = 1,
                sequence = 1,
                scopes = listOf(StoreInvalidationScope.MemoList, StoreInvalidationScope.Stats),
            ),
        )

        memoList.invalid shouldBe true
        trash.invalid shouldBe false
        bus.publications.value.coreRevision shouldBe 1
        bus.publications.value.eventSequence shouldBe 1
        bus.publications.value.scopes shouldContainExactly
            setOf(StoreInvalidationScope.MemoList, StoreInvalidationScope.Stats)
    }

    test("idempotent replay and older commit do not regress or repeat publication") {
        val bus = StoreInvalidationBus()
        bus.publish(
            commit(
                revision = 2,
                sequence = 2,
                scopes = listOf(StoreInvalidationScope.MemoList),
            ),
        )
        val afterCommit = ProjectionPagingSource()
        bus.register(afterCommit, setOf(StoreInvalidationScope.MemoList))

        bus.publish(
            commit(
                revision = 2,
                sequence = 2,
                scopes = emptyList(),
                idempotentReplay = true,
            ),
        )
        bus.publish(
            commit(
                revision = 1,
                sequence = 1,
                scopes = listOf(StoreInvalidationScope.MemoList),
            ),
        )

        afterCommit.invalid shouldBe false
        bus.publications.value.coreRevision shouldBe 2
        bus.publications.value.eventSequence shouldBe 2
    }

    test("event gap promotes scoped commit to full invalidation") {
        val bus = StoreInvalidationBus()
        bus.publish(commit(1, 1, listOf(StoreInvalidationScope.Stats)))
        val memoList = ProjectionPagingSource()
        bus.register(memoList, setOf(StoreInvalidationScope.MemoList))

        bus.publish(commit(3, 3, listOf(StoreInvalidationScope.Stats)))

        memoList.invalid shouldBe true
        bus.publications.value.scopes shouldContainExactly setOf(StoreInvalidationScope.Full)
    }

    test("higher rebuild revision full-invalidates once and stale rebuild is ignored") {
        val bus = StoreInvalidationBus()
        bus.publish(commit(1, 1, listOf(StoreInvalidationScope.MemoList)))
        val beforeRebuild = ProjectionPagingSource()
        bus.register(beforeRebuild, setOf(StoreInvalidationScope.Trash))

        bus.publishRebuild(highWaterRevision = 4)
        val afterRebuild = ProjectionPagingSource()
        bus.register(afterRebuild, setOf(StoreInvalidationScope.MemoList))
        bus.publishRebuild(highWaterRevision = 3)

        beforeRebuild.invalid shouldBe true
        afterRebuild.invalid shouldBe false
        bus.publications.value.coreRevision shouldBe 4
        bus.publications.value.eventSequence shouldBe null
        bus.publications.value.scopes shouldContainExactly setOf(StoreInvalidationScope.Full)
    }
})
