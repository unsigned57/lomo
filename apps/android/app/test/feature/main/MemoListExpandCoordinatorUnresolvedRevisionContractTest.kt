package com.lomo.app.feature.main

import com.lomo.app.testing.fakes.emptyRenderDocument
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/*
 * Behavior Contract:
 * - Unit under test: MemoListExpandCoordinator — unresolved request revisions.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: a request whose preview revision cannot be resolved (Memo.contentRevision is
 *   null while the row is off-window or the store does not version content) must never let a
 *   published snapshot skip validation — the snapshot cannot prove it carries the requested
 *   content, so the row reloads from the store truth rather than serving stale bytes.
 *
 * Scenarios:
 * - Given an expanded row whose request revision never resolved, when the underlying content
 *   changes and sync runs again with the revision still unresolved, then the row reloads and
 *   republishes the fresh snapshot.
 * - Given a cached Expanded snapshot under an unresolved request, when the row collapses and
 *   re-expands while the content moved, then the cached snapshot is not served — a fresh load
 *   publishes the current body.
 * - Given an unresolved request with a load still in flight, when sync repeats with the
 *   revision still unresolved, then the in-flight load is not restarted.
 *
 * Observable outcomes:
 * - Expanded snapshot bodies in the published states map and load invocation counts.
 *
 * TDD proof:
 * - Fails while sync() exempts UNRESOLVED_REVISION requests from snapshot validation, which
 *   lets stale Expanded snapshots survive repeat syncs and collapse/re-expansion.
 *
 * Excludes:
 * - Compose effects and the previewRevisions feed; the coordinator is driven directly.
 */
class MemoListExpandCoordinatorUnresolvedRevisionContractTest : FunSpec({

    fun memo(id: String, revision: Long, body: String = "body-$id"): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            updatedAt = revision,
            contentRevision = revision,
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
        loadFullMemo: suspend (String) -> Memo?,
    ) = MemoListExpandCoordinator(
        scope = scope,
        loadFullMemo = loadFullMemo,
        mapFullMemo = { loaded -> uiModel(loaded) },
    )

    test("an unresolved request revision never lets a published snapshot skip revalidation") {
        runTest {
            var revision = 1L
            var body = "first-body"
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    memo(id, revision = revision, body = body)
                }

            // The preview cannot resolve a revision for the expanded row (null), so the
            // request stays unresolved; the snapshot published under it cannot later prove
            // it still carries the current content.
            coordinator.sync("ws-a", mapOf("m1" to null))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
                .model.memo.content shouldBe "first-body"

            revision = 2L
            body = "second-body"
            coordinator.sync("ws-a", mapOf("m1" to null))
            testScheduler.advanceUntilIdle()

            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
                .model.memo.content shouldBe "second-body"
            loads shouldBe listOf("m1", "m1")
        }
    }

    test("collapse and re-expand under an unresolved request revision reloads instead of serving the cached snapshot") {
        runTest {
            var revision = 1L
            var body = "cached-body"
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    memo(id, revision = revision, body = body)
                }

            coordinator.sync("ws-a", mapOf("m1" to null))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()

            coordinator.sync("ws-a", emptyMap())

            revision = 2L
            body = "moved-body"
            coordinator.sync("ws-a", mapOf("m1" to null))
            testScheduler.advanceUntilIdle()

            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
                .model.memo.content shouldBe "moved-body"
            loads shouldBe listOf("m1", "m1")
        }
    }

    test("an unresolved request does not restart a load that is still in flight") {
        runTest {
            val gate = CompletableDeferred<Unit>()
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    gate.await()
                    memo(id, revision = 1)
                }

            coordinator.sync("ws-a", mapOf("m1" to null))
            yield()
            coordinator.sync("ws-a", mapOf("m1" to null))
            yield()

            loads shouldBe listOf("m1")
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Loading>()

            gate.complete(Unit)
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
        }
    }
})
