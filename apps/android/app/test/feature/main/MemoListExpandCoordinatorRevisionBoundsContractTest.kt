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
 * - Unit under test: MemoListExpandCoordinator — unresolved/unversioned revisions, terminal
 *   failure bounds, and the surviving role of the unresolved sentinel.
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: removing the UNRESOLVED_REVISION exemption must not create an unbounded reload
 *   loop — a Failed entry never auto-resurrects (a repeat sync keeps it terminal), an
 *   unprovable snapshot reloads at most once per sync call (linear in external sync triggers,
 *   never self-feeding), and a resolved request keeps its last revision when the preview later
 *   reports null (null means "keep", not "reset to unresolved").
 *
 * Scenarios:
 * - Given an expanded row whose load failed under an unresolved request, when sync repeats,
 *   then the row stays Failed — the unconditional snapshot check never revives a terminal
 *   failure into a retry storm.
 * - Given a resolved request whose loaded snapshot carries no contentRevision (unversioned
 *   store), when sync repeats with the same revision, then the snapshot still cannot prove the
 *   revision and reloads once per sync — bounded, linear.
 * - Given a resolved request whose preview then reports null (row left the loaded window),
 *   when sync runs, then the request keeps its last revision and the matching snapshot serves.
 *
 * Observable outcomes: states map entries and load invocation counts.
 *
 * TDD proof:
 * - The Failed-stays-terminal pin fails if a `want == existing.request` re-check ever
 *   re-armed Failed entries; the keep-last pin fails if a null preview revision reset the
 *   request revision instead of retaining it.
 *
 * Excludes:
 * - Compose effects and the previewRevisions feed; the coordinator is driven directly.
 */
class MemoListExpandCoordinatorRevisionBoundsContractTest : FunSpec({

    fun memo(id: String, revision: Long?, body: String = "body-$id"): Memo =
        Memo(
            id = id,
            timestamp = 1L,
            updatedAt = revision ?: 0L,
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

    test("a terminal Failed under an unresolved request never auto-resurrects on repeat syncs") {
        runTest {
            var fail = true
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    if (fail) throw IllegalStateException("read failed")
                    memo(id, revision = null)
                }

            coordinator.sync("ws-a", mapOf("m1" to null))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Failed>()

            // The store could serve the row now, but a terminal failure under an unresolved
            // request must not loop: each repeat sync keeps it Failed until retryExpand.
            fail = false
            coordinator.sync("ws-a", mapOf("m1" to null))
            coordinator.sync("ws-a", mapOf("m1" to null))
            testScheduler.advanceUntilIdle()

            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Failed>()
            loads shouldBe listOf("m1")
        }
    }

    test("a resolved request whose loaded snapshot is unversioned reloads once per sync — linear, not a hot loop") {
        runTest {
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    memo(id, revision = null, body = "unversioned")
                }

            coordinator.sync("ws-a", mapOf("m1" to 7L))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()

            // A snapshot that carries no contentRevision can never self-prove a resolved
            // request revision: each external sync reloads it once — bounded linear, driven
            // only by sync triggers, never by the coordinator's own publishes.
            coordinator.sync("ws-a", mapOf("m1" to 7L))
            testScheduler.advanceUntilIdle()
            coordinator.sync("ws-a", mapOf("m1" to 7L))
            testScheduler.advanceUntilIdle()

            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
            loads shouldBe listOf("m1", "m1", "m1")
        }
    }

    test("a null preview revision keeps the last resolved request revision and serves the matching snapshot") {
        runTest {
            val loads = mutableListOf<String>()
            val coordinator =
                coordinator(this) { id ->
                    loads += id
                    memo(id, revision = 7, body = "resolved-body")
                }

            coordinator.sync("ws-a", mapOf("m1" to 7L))
            testScheduler.advanceUntilIdle()
            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()

            // The row left the loaded preview window (revision now null): the request retains
            // revision 7 and the snapshot — which proves 7 — serves without a reload.
            coordinator.sync("ws-a", mapOf("m1" to null))
            testScheduler.advanceUntilIdle()

            coordinator.states.value["m1"].shouldBeInstanceOf<MemoExpandEntry.Expanded>()
                .model.memo.content shouldBe "resolved-body"
            loads shouldBe listOf("m1")
        }
    }
})
