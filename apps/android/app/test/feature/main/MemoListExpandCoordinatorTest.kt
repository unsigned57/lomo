package com.lomo.app.feature.main

import com.lomo.app.testing.fakes.emptyRenderDocument
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.collections.immutable.persistentListOf

/*
 * Behavior Contract:
 * - Unit under test: MemoListExpandCoordinator
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: drive row expansion as an explicit Collapsed/Loading/Expanded(snapshot)/Failed
 *   state machine bound to memo identity, content revision, and workspace epoch, with bounded
 *   concurrency and a byte-bounded full-body cache.
 *
 * Scenarios:
 * - Given a long preview row is expanded, when the full snapshot arrives, then the row exposes
 *   Expanded with the full model — never the preview body at unlimited height.
 * - Given an expand request is in flight, when states are observed, then Loading is explicit
 *   rather than the preview pretending to be the full body.
 * - Given a row is collapsed and re-expanded under the same revision, when expansion completes,
 *   then the cached full snapshot is reused without a second load.
 * - Given the content revision moves while a load is in flight, when the stale result lands,
 *   then it is rejected and the newer revision is loaded instead.
 * - Given the workspace epoch changes, when sync runs, then snapshots from the old epoch are
 *   cleared.
 * - Given a read failure, when the row retries, then the full snapshot loads again and no stale
 *   body was ever published.
 * - Given expanded full bodies exceed the byte budget, when more loads complete, then cached
 *   snapshots for rows no longer requested are evicted first.
 * - Given more expand requests than the concurrency bound, when sync runs, then only the bound
 *   number of loads run at once.
 *
 * Observable outcomes:
 * - states map transitions (Loading → Expanded/Failed), load call counts, eviction order.
 *
 * TDD proof:
 * - Fails while expansion is a bare id→model cache without revision/epoch binding, explicit
 *   states, concurrency or byte bounds.
 *
 * Excludes:
 * - Compose effects, koin wiring, and pixel rendering of the loading/failure affordances.
 */
class MemoListExpandCoordinatorTest : FunSpec({

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
        maxConcurrentLoads: Int = 4,
        maxExpandedBodyBytes: Long = 4 * 1024 * 1024,
        loadFullMemo: suspend (String) -> Memo?,
    ) = MemoListExpandCoordinator(
        scope = scope,
        loadFullMemo = loadFullMemo,
        mapFullMemo = { loaded -> uiModel(loaded) },
        maxConcurrentLoads = maxConcurrentLoads,
        maxExpandedBodyBytes = maxExpandedBodyBytes,
    )

    test("expanding a row publishes Loading then Expanded with the full snapshot") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    gate.await()
                    memo(id, revision = 7, body = "x".repeat(700))
                }

            coordinator.sync("ws-a", mapOf("m1" to 7L))
            yield()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Loading>()

            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            val expanded = coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
            expanded.model.memo.content.length shouldBe 700
            expanded.model.memo.contentKind shouldBe MemoContentKind.Full
            loads shouldBe listOf("m1")
        }
    }

    test("collapse and re-expand under the same revision reuses the cached full snapshot") {
        runTest {
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    memo(id, revision = 3)
                }

            coordinator.sync("ws-a", mapOf("m1" to 3L))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()

            coordinator.sync("ws-a", emptyMap())
            coordinator.sync("ws-a", mapOf("m1" to 3L))
            testScheduler.advanceUntilIdle()

            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
            loads shouldBe listOf("m1")
        }
    }

    test("a late result for a superseded revision is rejected and the newer revision loads") {
        runTest {
            val firstGate = CompletableDeferred<Unit>()
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    if (loads.size == 1) firstGate.await()
                    memo(id, revision = loads.size.toLong())
                }

            coordinator.sync("ws-a", mapOf("m1" to 1L))
            yield()
            coordinator.sync("ws-a", mapOf("m1" to 2L))
            firstGate.complete(Unit)
            testScheduler.advanceUntilIdle()

            val expanded = coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
            expanded.model.memo.updatedAt shouldBe 2L
            loads.size shouldBe 2
        }
    }

    test("a workspace epoch change clears every snapshot bound to the old workspace") {
        runTest {
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    memo(id, revision = 1)
                }

            coordinator.sync("ws-a", mapOf("m1" to 1L))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()

            coordinator.sync("ws-b", mapOf("m1" to 1L))
            testScheduler.advanceUntilIdle()

            val expanded = coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
            expanded.model.memo.content shouldBe "body-m1"
            loads shouldBe listOf("m1", "m1")
        }
    }

    test("a read failure publishes Failed and retry loads again without a stale body") {
        runTest {
            var attempts = 0
            val coordinator =
                coordinator(this) { id ->
                    attempts += 1
                    if (attempts == 1) throw IllegalStateException("read failed")
                    memo(id, revision = 5)
                }

            coordinator.sync("ws-a", mapOf("m1" to 5L))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Failed>()

            coordinator.retryExpand("m1")
            testScheduler.advanceUntilIdle()

            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
            attempts shouldBe 2
        }
    }

    test("a missing memo publishes Failed rather than a stale full body") {
        runTest {
            val coordinator = coordinator(this) { null }

            coordinator.sync("ws-a", mapOf("m1" to 1L))
            testScheduler.advanceUntilIdle()

            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Failed>()
        }
    }

    test("expanded bodies beyond the byte budget evict snapshots for rows no longer requested") {
        runTest {
            val coordinator =
                coordinator(this, maxExpandedBodyBytes = 250) { id ->
                    memo(id, revision = 1, body = "x".repeat(100))
                }

            coordinator.sync("ws-a", mapOf("m1" to 1L, "m2" to 1L))
            testScheduler.advanceUntilIdle()
            coordinator.sync("ws-a", mapOf("m2" to 1L, "m3" to 1L))
            testScheduler.advanceUntilIdle()

            coordinator.states.value["m1"] shouldBe null
            coordinator.states.value["m2"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
            coordinator.states.value["m3"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
        }
    }

    test("concurrent loads never exceed the configured bound") {
        runTest {
            var inFlight = 0
            var peak = 0
            val release = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(this, maxConcurrentLoads = 2) { id ->
                    inFlight += 1
                    peak = maxOf(peak, inFlight)
                    release.await()
                    inFlight -= 1
                    memo(id, revision = 1)
                }

            coordinator.sync("ws-a", mapOf("m1" to 1L, "m2" to 1L, "m3" to 1L, "m4" to 1L))
            yield()
            peak shouldBe 2

            release.complete(Unit)
            testScheduler.advanceUntilIdle()
            coordinator.states.value.size shouldBe 4
        }
    }
})
