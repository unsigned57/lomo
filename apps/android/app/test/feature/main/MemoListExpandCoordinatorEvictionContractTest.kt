// adversarial-audit: hypotheses under test:
// 1. An Expanded row that is still requested must never be evicted into a void that leaves the
//    row spinning forever: today an over-budget body is evicted even while desired, publish()
//    drops it from states, and no reload is scheduled until the next unrelated sync — the row
//    shows LinearProgressIndicator with nothing in flight.
// 2. The request key's "revision" is bound to memo.updatedAt by the production bridge, not to
//    contentRevision/fileFingerprint: two distinct contents sharing updatedAt (preserved-mtime
//    sync writes, same-ms edits) publish the stale snapshot forever.
// 3. Failed entries are never evicted and survive collapse, so the published states map retains
//    failures for rows the user no longer expanded.
package com.lomo.app.feature.main

import com.lomo.app.testing.fakes.emptyRenderDocument
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: MemoListExpandCoordinator eviction and revision binding.
 * - Owning layer: app.
 * - Priority tier: P0.
 * - Capability: a still-requested expanded row is never evicted into a spinner-only void; the
 *   request key binds content revision, not updatedAt; failed entries for collapsed rows are
 *   released rather than retained.
 *
 * Scenarios:
 * - Given an over-budget expanded row still requested, when eviction runs, then it does not drop
 *   into a spinner-only void.
 * - Given content changed under an identical observed revision, when re-requested, then a fresh
 *   snapshot republishes.
 * - Given a failed expansion for a collapsed row, when publish runs, then the failure is released
 *   instead of retained in states.
 *
 * Observable outcomes: published states map contents, snapshot freshness, failure retention.
 *
 * TDD proof:
 * - Each arm fails RED against the bare id→model cache described in the audit note; GREEN under
 *   the revision-bound, eviction-aware coordinator.
 *
 * Excludes:
 * - Compose effects and pixel rendering of loading/failure affordances.
 */
class MemoListExpandCoordinatorEvictionContractTest : FunSpec({

    fun memo(id: String, revision: Long, body: String = "body-$id"): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            updatedAt = revision,
            content = body,
            rawContent = body,
            dateKey = "2026_06_27",
            contentKind = MemoContentKind.Full,
        )

    fun uiModel(memo: Memo): MemoUiModel =
        MemoUiModel(
            memo = memo,
            processedContent = memo.content,
            renderDocument = emptyRenderDocument(),
            tags = persistentListOf(),
            imageUrls = persistentListOf(),
            shouldShowExpand = true,
            collapsedSummary = "",
            reminders = persistentListOf(),
        )

    fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        maxExpandedBodyBytes: Long = 4 * 1024 * 1024,
        loadFullMemo: suspend (String) -> Memo?,
    ) = MemoListExpandCoordinator(
        scope = scope,
        loadFullMemo = loadFullMemo,
        mapFullMemo = { loaded -> uiModel(loaded) },
        maxExpandedBodyBytes = maxExpandedBodyBytes,
    )

    test("an over-budget expanded row that is still requested must not drop into a spinner-only void") {
        runTest {
            val coordinator =
                coordinator(this, maxExpandedBodyBytes = 64) { id ->
                    memo(id, revision = 1, body = "x".repeat(100))
                }

            coordinator.sync("ws-a", mapOf("m1" to 1L))
            testScheduler.advanceUntilIdle()

            // Desired outcome: the still-expanded row keeps its snapshot (or at least fails
            // loudly). Actual: evictOverflow removes the only desired Expanded entry, publish()
            // drops the key entirely, and the UI renders a bare LinearProgressIndicator with no
            // load in flight — a permanently stuck expand.
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
        }
    }

    test("content changed under an identical observed revision republishes a fresh snapshot") {
        runTest {
            var body = "first-body"
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    memo(id, revision = 7, body = body)
                }

            coordinator.sync("ws-a", mapOf("m1" to 7L))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
                .model.memo.content shouldBe "first-body"

            // External writer (sync apply / restore) changes the body while the row-visible
            // revision field (updatedAt, what rememberMemoListExpandStates feeds in) stays equal.
            body = "second-body"
            coordinator.sync("ws-a", mapOf("m1" to 7L))
            testScheduler.advanceUntilIdle()

            // Desired: the expanded row republishes the new content. Actual: want == request so
            // the stale Expanded snapshot is kept and no reload runs — contentRevision exists on
            // Memo precisely because updatedAt is not a reliable content identity.
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
                .model.memo.content shouldBe "second-body"
            loads.size shouldBe 2
        }
    }

    test("a failed expansion for a collapsed row is released instead of retained in states") {
        runTest {
            val coordinator =
                coordinator(this) { throw IllegalStateException("read failed") }

            coordinator.sync("ws-a", mapOf("m1" to 1L))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Failed>()

            coordinator.sync("ws-a", emptyMap())
            testScheduler.advanceUntilIdle()

            // Desired: collapsing releases the terminal failure like it cancels a Loading.
            // Actual: sync only removes Loading entries on collapse; Failed (and Expanded) stay
            // tracked and keep publishing into the states map for the rest of the session.
            coordinator.states.value["m1"] shouldBe null
        }
    }
})
