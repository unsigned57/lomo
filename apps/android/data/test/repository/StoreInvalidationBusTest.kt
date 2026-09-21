package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: StoreInvalidationBus.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: publish Rust store commits as one monotonic, scoped projection-version stream.
 *
 * Scenarios:
 * - Given memo-list and trash consumers, when any accepted commit is published, then every
 *   paging consumer invalidates because every commit advances the high-water revision used by
 *   every opaque cursor; Rust scopes remain publication labels for non-paging observers.
 * - Given an already-published commit, when its idempotent replay or an older commit arrives, then
 *   no publication regresses and a newly registered consumer remains valid.
 * - Given a missing event sequence, when the later commit arrives, then its scopes are promoted to
 *   Full and every registered projection consumer invalidates.
 * - Given a completed rebuild, when its higher high-water revision is published, then consumers
 *   full-invalidate once; a stale rebuild result is ignored.
 * - Given a mid-flight pending create, when the durable commit confirms, then the publication clock
 *   advances and paging sources already rebuilt by the pending publish are not invalidated again.
 * - Given a replaced projection (archive import) whose high-water is lower, when it is re-anchored,
 *   then the next commit on the new projection publishes instead of being silently dropped.
 * - Given a high-water workspace clock, when reanchor installs a lower-water generation, then the
 *   next commit on the new store publishes instead of being silently dropped.
 * - Given event sequence advances without core revision, or regresses while revision advances,
 *   when the publication is accepted, then scopes promote to Full instead of crashing the caller.
 * - Given a receipt with a non-positive revision or sequence, when it is published, then a
 *   structured ProtocolFailure is raised instead of an unclassifiable IllegalArgumentException.
 *
 * Observable outcomes:
 * - PagingSource invalid state and StoreProjectionPublication revision, sequence, and scopes.
 *
 * TDD proof:
 * - RED on 2026-08-09: StoreInvalidationBus exposed only an untyped bump counter, so commit order,
 *   replay identity, lost-event gaps, rebuild high-water revisions, and scoped invalidation were
 *   impossible to represent.
 * - RED on 2026-09-12: native-owned projection events had no bus entry that invalidates paging
 *   without fabricating a per-memo StoreMemoCommit.
 * - RED on 2026-09-12: switching to a lower-water workspace left lastCoreRevision high, so
 *   acceptPublication dropped every new-store commit; monotonic contradictions threw.
 *
 * Excludes:
 * - Store query contents, JNI transport, Compose rendering, and native event subscription.
 * Test Change Justification:
 * - Reason category: domain contract change (publication clock, reanchor, structured failure).
 * - Old behavior/assertion being replaced: register(scope-set) API and revision-only acceptance.
 * - Why old assertion is no longer correct: publication now tracks the workspace generation
 *   high-water with reanchor; scope-set registration was removed.
 * - Coverage preserved by: existing invalidation scenarios plus new cases for pending-create
 *   skip, lower-water reanchor, monotonic-contradiction promotion, and malformed receipts.
 * - Why this is not fitting the test to the implementation: assertions pin observable
 *   publish/drop/promote outcomes.
 */

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lomo.data.engine.store.StoreInvalidationScope
import com.lomo.data.engine.store.StoreMemoCommit
import io.kotest.assertions.throwables.shouldThrow
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
    test("every accepted commit invalidates every paging consumer") {
        val bus = StoreInvalidationBus()
        val memoList = ProjectionPagingSource()
        val trash = ProjectionPagingSource()
        bus.register(memoList)
        bus.register(trash)

        bus.publish(
            commit(
                revision = 1,
                sequence = 1,
                scopes = listOf(StoreInvalidationScope.MemoList, StoreInvalidationScope.Stats),
            ),
        )

        memoList.invalid shouldBe true
        trash.invalid shouldBe true
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
        bus.register(afterCommit)

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
        bus.register(memoList)

        bus.publish(commit(3, 3, listOf(StoreInvalidationScope.Stats)))

        memoList.invalid shouldBe true
        bus.publications.value.scopes shouldContainExactly setOf(StoreInvalidationScope.Full)
    }

    test("higher rebuild revision full-invalidates once and stale rebuild is ignored") {
        val bus = StoreInvalidationBus()
        bus.publish(commit(1, 1, listOf(StoreInvalidationScope.MemoList)))
        val beforeRebuild = ProjectionPagingSource()
        bus.register(beforeRebuild)
        bus.publishRebuild(highWaterRevision = 4)
        val afterRebuild = ProjectionPagingSource()
        bus.register(afterRebuild)
        bus.publishRebuild(highWaterRevision = 3)

        beforeRebuild.invalid shouldBe true
        afterRebuild.invalid shouldBe false
        bus.publications.value.coreRevision shouldBe 4
        bus.publications.value.eventSequence shouldBe null
        bus.publications.value.scopes shouldContainExactly setOf(StoreInvalidationScope.Full)
    }

    test("confirming a later commit advances the clock without invalidating paging again") {
        val bus = StoreInvalidationBus()
        val pending = ProjectionPagingSource()
        bus.register(pending)
        bus.publish(
            commit(
                revision = 1,
                sequence = 1,
                scopes = listOf(StoreInvalidationScope.MemoList),
            ),
        )
        val afterPending = ProjectionPagingSource()
        bus.register(afterPending)

        bus.confirm(
            commit(
                revision = 2,
                sequence = 2,
                scopes = listOf(StoreInvalidationScope.MemoList, StoreInvalidationScope.Stats),
            ),
        )

        pending.invalid shouldBe true
        afterPending.invalid shouldBe false
        bus.publications.value.coreRevision shouldBe 2
        bus.publications.value.eventSequence shouldBe 2
    }

    test("archive projection replacement re-anchors to a lower high-water and accepts the next commit") {
        val bus = StoreInvalidationBus()
        bus.publish(commit(9, 9, listOf(StoreInvalidationScope.MemoList)))
        val beforeReplace = ProjectionPagingSource()
        bus.register(beforeReplace)

        bus.reanchorProjection(highWaterRevision = 2)
        val afterReplace = ProjectionPagingSource()
        bus.register(afterReplace)
        bus.publish(commit(3, 3, listOf(StoreInvalidationScope.MemoList)))

        beforeReplace.invalid shouldBe true
        afterReplace.invalid shouldBe true
        bus.publications.value.coreRevision shouldBe 3
        bus.publications.value.eventSequence shouldBe 3
    }

    test("a receipt violating the publication protocol surfaces a structured ProtocolFailure") {
        val bus = StoreInvalidationBus()
        val memoList = ProjectionPagingSource()
        bus.register(memoList)

        val failure =
            shouldThrow<com.lomo.domain.model.EngineCommandFailureException> {
                bus.publish(commit(revision = 0, sequence = 0, scopes = emptyList()))
            }

        failure.failure.code shouldBe StoreInvalidationBus.PROTOCOL_FAILURE_CODE
        memoList.invalid shouldBe false
    }

    test("reanchor to a lower-water generation accepts the new store's next commit") {
        val bus = StoreInvalidationBus()
        bus.publish(commit(10, 10, listOf(StoreInvalidationScope.MemoList)))
        val beforeSwitch = ProjectionPagingSource()
        bus.register(beforeSwitch)

        bus.reanchor(generation = 2, highWaterRevision = 3)
        val afterSwitch = ProjectionPagingSource()
        bus.register(afterSwitch)
        bus.publish(commit(4, 4, listOf(StoreInvalidationScope.MemoList)))

        beforeSwitch.invalid shouldBe true
        afterSwitch.invalid shouldBe true
        bus.publications.value.coreRevision shouldBe 4
        bus.publications.value.eventSequence shouldBe 4
    }

    test("sequence without revision or sequence regression full-invalidates instead of crashing") {
        val bus = StoreInvalidationBus()
        bus.publish(commit(1, 1, listOf(StoreInvalidationScope.MemoList)))
        val afterContradiction = ProjectionPagingSource()
        bus.register(afterContradiction)

        bus.publish(commit(1, 2, listOf(StoreInvalidationScope.MemoList)))

        afterContradiction.invalid shouldBe true
        bus.publications.value.coreRevision shouldBe 1
        bus.publications.value.eventSequence shouldBe 2
        bus.publications.value.scopes shouldContainExactly setOf(StoreInvalidationScope.Full)

        val afterRegression = ProjectionPagingSource()
        bus.register(afterRegression)
        bus.publish(commit(3, 2, listOf(StoreInvalidationScope.Stats)))

        afterRegression.invalid shouldBe true
        bus.publications.value.coreRevision shouldBe 3
        bus.publications.value.eventSequence shouldBe 2
        bus.publications.value.scopes shouldContainExactly setOf(StoreInvalidationScope.Full)
    }
})
