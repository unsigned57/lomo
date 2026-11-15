package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import java.time.LocalDate
import java.time.ZoneId

/*
 * Behavior Contract:
 * - Unit under test: memo list expansion state helpers (expandedRowCardModel,
 *   updateExpandedMemoIds).
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: a row whose collapsed preview truncates the body — or that is inside a live
 *   expansion session — always presents the expand/collapse affordance on its card model.
 *   The expanded Full snapshot's own predicate is calibrated to a different budget and must not
 *   decide the row affordance.
 *
 * Scenarios:
 * - Given a Full snapshot model whose body is below the card expand budget
 *   (shouldShowExpand=false), when the row requires the affordance, then the presented card
 *   model keeps it so the footer toggle and body-tap stay armed.
 * - Given the same model, when the row does not require the affordance, then the model is
 *   returned untouched.
 * - Given a model that already reports the affordance, when required, then no copy is produced.
 *
 * Observable outcomes: MemoUiModel.shouldShowExpand on the presented card model; identity of the
 * returned instance.
 *
 * TDD proof:
 * - Fails before the M3E D1 fix: the row presented the expanded Full model verbatim, so a
 *   200..600-char body expanded into a card whose footer hid the collapse toggle, whose body tap
 *   was inert, and whose collapsed row lost the re-expand pill once the expansion ended.
 * - RED: `./kotlin test --include-module=app --include-classes='com.lomo.app.feature.main.MemoExpansionStateTest'` failed 3/9 tests (AssertionFailedError expected:<false> but was:<true> / expected:<true> but was:<false>) because expandedRowSourceModel returned the cached Full/Refreshing snapshot verbatim when pin/pending flags changed without advancing contentRevision; GREEN: same command + MemoRefreshContinuityTest passed 10/10 after projecting live preview flags onto expanded rows.
 *
 * Excludes: Compose rendering, LazyListState, the expand coordinator's paging fetches.
 * Production scope: apps/android/app/src/feature/main/MemoExpansionState.kt
 * Test Change Justification:
 * Reason category: additional regression coverage for live row metadata.
 * Old behavior/assertion being replaced: none; existing expansion and affordance assertions remain.
 * Why old assertion is no longer correct: existing assertions remain correct but did not cover pin
 * and pending changes while the full snapshot stays cached.
 * Coverage preserved by: all existing cases plus expanded/refreshing snapshot metadata assertions.
 * Why this is not fitting the test to the implementation: the displayed body must stay expanded
 * while pin and pending indicators reflect the current preview publication.
 */
class MemoExpansionStateTest : AppFunSpec() {
    init {
        test("row that requires the affordance keeps it even when the Full model reports none") {
            val model = memoUiModel(shouldShowExpand = false)

            val presented = expandedRowCardModel(uiModel = model, affordanceRequired = true)

            presented.shouldShowExpand shouldBe true
            presented.memo.id shouldBe model.memo.id
        }

        test("row without an affordance requirement presents the model's own verdict untouched") {
            val model = memoUiModel(shouldShowExpand = false)

            val presented = expandedRowCardModel(uiModel = model, affordanceRequired = false)

            presented.shouldShowExpand shouldBe false
            presented shouldBe model
        }

        test("affordant model is not copied when the row requires it") {
            val model = memoUiModel(shouldShowExpand = true)

            val presented = expandedRowCardModel(uiModel = model, affordanceRequired = true)

            presented shouldBe model
        }

        // A collapsed row must present the preview model even while the coordinator keeps the
        // Expanded snapshot cached — otherwise the clipped preview leaks the full body.
        test("collapsed row presents the preview model while an Expanded snapshot stays cached") {
            val preview = memoUiModel(shouldShowExpand = true)
            val full = memoUiModel(shouldShowExpand = false)

            val presented =
                expandedRowSourceModel(
                    previewModel = preview,
                    expandEntry = MemoExpandEntry.Expanded(model = full),
                    expandRequested = false,
                )

            presented shouldBe preview
        }

        test("actively expanded row presents the cached Full snapshot") {
            val preview = memoUiModel(shouldShowExpand = true)
            val full = memoUiModel(shouldShowExpand = false)

            val presented =
                expandedRowSourceModel(
                    previewModel = preview,
                    expandEntry = MemoExpandEntry.Expanded(model = full),
                    expandRequested = true,
                )

            presented shouldBe full
        }

        test("requested row without a published snapshot still presents the preview model") {
            val preview = memoUiModel(shouldShowExpand = true)

            val presented =
                expandedRowSourceModel(
                    previewModel = preview,
                    expandEntry = MemoExpandEntry.Loading,
                    expandRequested = true,
                )

            presented shouldBe preview
        }

        test("actively expanded row reflects live unpin and pending metadata from preview model") {
            val preview =
                memoUiModel(
                    shouldShowExpand = true,
                    content = "preview body",
                    contentKind = com.lomo.domain.model.MemoContentKind.Preview,
                    isPinned = false,
                    isPending = true,
                )
            val full =
                memoUiModel(
                    shouldShowExpand = false,
                    content = "full body",
                    contentKind = com.lomo.domain.model.MemoContentKind.Full,
                    isPinned = true,
                    isPending = false,
                )

            val presented =
                expandedRowSourceModel(
                    previewModel = preview,
                    expandEntry = MemoExpandEntry.Expanded(model = full),
                    expandRequested = true,
                )

            presented.memo.content shouldBe "full body"
            presented.memo.contentKind shouldBe com.lomo.domain.model.MemoContentKind.Full
            presented.memo.isPinned shouldBe false
            presented.memo.isPending shouldBe true
        }

        test("actively expanded row reflects live pin metadata when pinned while expanded") {
            val preview =
                memoUiModel(
                    shouldShowExpand = true,
                    content = "preview body",
                    contentKind = com.lomo.domain.model.MemoContentKind.Preview,
                    isPinned = true,
                )
            val full =
                memoUiModel(
                    shouldShowExpand = false,
                    content = "full body",
                    contentKind = com.lomo.domain.model.MemoContentKind.Full,
                    isPinned = false,
                )

            val presented =
                expandedRowSourceModel(
                    previewModel = preview,
                    expandEntry = MemoExpandEntry.Expanded(model = full),
                    expandRequested = true,
                )

            presented.memo.content shouldBe "full body"
            presented.memo.isPinned shouldBe true
        }

        test("refreshing expanded row reflects live unpin metadata while keeping previous full body") {
            val preview =
                memoUiModel(
                    shouldShowExpand = true,
                    content = "new preview",
                    contentKind = com.lomo.domain.model.MemoContentKind.Preview,
                    isPinned = false,
                )
            val previous =
                memoUiModel(
                    shouldShowExpand = false,
                    content = "old full body",
                    contentKind = com.lomo.domain.model.MemoContentKind.Full,
                    isPinned = true,
                )

            val presented =
                expandedRowSourceModel(
                    previewModel = preview,
                    expandEntry = MemoExpandEntry.Refreshing(previous = previous),
                    expandRequested = true,
                )

            presented.memo.content shouldBe "old full body"
            presented.memo.isPinned shouldBe false
        }
    }

    private fun memoUiModel(
        shouldShowExpand: Boolean,
        content: String = "full body",
        contentKind: com.lomo.domain.model.MemoContentKind = com.lomo.domain.model.MemoContentKind.Full,
        isPinned: Boolean = false,
        isPending: Boolean = false,
    ): MemoUiModel = MemoUiModel(
        memo =
        Memo(
            id = "memo-1",
            timestamp =
            LocalDate.of(2026, 4, 10)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
            content = content,
            rawContent = content,
            dateKey = "2026_04_10",
            localDate = LocalDate.of(2026, 4, 10),
            isPinned = isPinned,
            isPending = isPending,
            contentKind = contentKind,
        ),
        processedContent = content,
        renderDocument = com.lomo.app.testing.fakes.emptyRenderDocument(),
        tags = persistentListOf(),
        shouldShowExpand = shouldShowExpand,
    )
}
