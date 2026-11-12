package com.lomo.app.feature.main

import com.lomo.app.testing.fakes.emptyRenderDocument
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: an expanded memo keeps its geometry while a committed todo update reloads;
 * owning layer: app; priority: P1.
 * Scenarios:
 * - Given an expanded full snapshot, when a newer revision starts loading, then the row
 *   keeps presenting the last confirmed full body until its replacement arrives.
 * - Given refresh is pending, when collapsed, then only the current bounded preview is shown.
 * - Given refresh completes, when the row is presented, then the newer full body replaces it.
 * - Given the workspace changes, when its first load waits, then no old full body is retained.
 * Observable outcomes: presented full/preview content during refresh and after replacement.
 * TDD proof: RED refresh-red.log (bounded preview replaced full body); GREEN app-green.log,
 * under target/lomo/reports/manual/interaction-latency/.
 * Production scope: apps/android/app/src/feature/main/MemoListExpandCoordinator.kt
 * Production scope: apps/android/app/src/feature/main/MemoExpansionState.kt
 * Excludes: durable write correctness and physical-device frame timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoRefreshContinuityTest :
    FunSpec({
        test("given an expanded memo when a todo revision reloads then the full body stays until replaced") {
            runTest {
                fun model(content: String, revision: Long, kind: MemoContentKind) = MemoUiModel(
                    memo = Memo(
                        id = "memo",
                        timestamp = 1L,
                        contentRevision = revision,
                        content = content,
                        rawContent = content,
                        dateKey = "2026_11_01",
                        contentKind = kind,
                    ),
                    processedContent = content,
                    renderDocument = emptyRenderDocument(content),
                    tags = persistentListOf(),
                    imageUrls = persistentListOf(),
                    reminders = persistentListOf(),
                    shouldShowExpand = true,
                    collapsedSummary = "",
                )
                val old = model("full unchecked body", 1L, MemoContentKind.Full)
                val current = model("full checked body", 2L, MemoContentKind.Full)
                val preview = model("bounded checked preview", 2L, MemoContentKind.Preview)
                val refresh = CompletableDeferred<Unit>()
                var loaded = old
                var readGate = CompletableDeferred(Unit)
                val coordinator = MemoListExpandCoordinator(
                    scope = backgroundScope,
                    loadFullMemo = {
                        readGate.await()
                        loaded.memo
                    },
                    mapFullMemo = { if (it.contentRevision == 1L) old else current },
                )
                coordinator.sync("workspace-a", mapOf("memo" to 1L))
                runCurrent()
                loaded = current
                readGate = refresh
                coordinator.sync("workspace-a", mapOf("memo" to 2L))
                runCurrent()
                fun presented(expanded: Boolean) = expandedRowSourceModel(
                    preview,
                    coordinator.states.value["memo"],
                    expanded,
                )
                presented(true).memo.content shouldBe old.memo.content
                presented(false) shouldBe preview
                refresh.complete(Unit)
                runCurrent()
                presented(true) shouldBe current

                readGate = CompletableDeferred()
                coordinator.sync("workspace-b", mapOf("memo" to 2L))
                runCurrent()
                presented(true) shouldBe preview
            }
        }
    })
