package com.lomo.app.feature.main

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.paging.compose.LazyPagingItems
import com.lomo.app.testing.fakes.emptyRenderDocument
import com.lomo.domain.model.Memo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: start creation entry as soon as its exact row and viewport are ready; owner: app; P1.
 * Scenarios:
 * - Given the committed row already placed at the top, when entry is acknowledged, then no
 *   new animation frame is required to acknowledge the already settled viewport.
 * - Given positioning is needed, when the layout has not acknowledged it, then entry waits;
 *   when the exact row and anchor arrive, then acknowledgement completes without another frame.
 * - Given an unmeasured held row below pinned memos, when placement is requested, then entry starts without waiting for its own height.
 * - Given a different paging identity, when the viewport is ready, then it cannot reveal that row.
 * Observable outcomes: acknowledgement completion before a new frame and exact-identity rejection.
 * TDD proof: viewport-red.log fails because acknowledgement is still suspended with a ready viewport;
 * viewport-green.log verifies the fix under target/lomo/reports/manual/creation-start-latency/.
 * Test Change Justification:
 * Reason category: eliminate an idle layout barrier after a verified head reaches a settled top.
 * Old behavior/assertion being replaced: none; an additional scenario covers the previous layout still showing the old head.
 * Why old assertion is no longer correct: existing assertions remain valid; a measured new head is not required to start its own first frame.
 * Coverage preserved by: exact identity rejection, below-top waiting, and already-measured-head acknowledgement.
 * Why this is not fitting the test to the implementation: completion is constrained by the committed paging identity and actual scroll state.
 * Additional TDD proof: target/lomo/reports/manual/four-bugs/viewport-red.log fails with result.isCompleted=false; GREEN uses the same spec.
 * Pinned-row TDD proof: target/lomo/reports/manual/directory-interaction-bugs/lomo-viewport-red.log
 * reports isCompleted=false before the first frame; lomo-bugs-app-green.log verifies immediate placement.
 * Production scope: apps/android/app/src/feature/main/MainListCreationViewport.kt
 * Excludes: persistence, Android layout cost, and animation phase ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainListCreationViewportTest :
    FunSpec({
        fun layout(key: String, index: Int): LazyListLayoutInfo {
            val item = mockk<LazyListItemInfo>()
            every { item.key } returns key
            every { item.index } returns index
            every { item.size } returns 0 // The committed row is deliberately held before entry.
            val info = mockk<LazyListLayoutInfo>()
            every { info.visibleItemsInfo } returns listOf(item)
            return info
        }
        fun paging(id: String, rank: Int = 0): LazyPagingItems<MemoUiModel> {
            val model = MemoUiModel(
                memo = Memo(id = id, timestamp = 1L, content = "memo", rawContent = "memo", dateKey = "2026_11_01"),
                processedContent = "memo",
                renderDocument = emptyRenderDocument("memo"),
                tags = persistentListOf(),
                imageUrls = persistentListOf(),
                reminders = persistentListOf(),
                shouldShowExpand = false,
                collapsedSummary = "",
            )
            val items = mockk<LazyPagingItems<MemoUiModel>>()
            every { items.itemCount } returns rank + 1
            every { items.peek(rank) } returns model
            return items
        }
        test(
            "given a zero height held row below pinned memos then entry starts with the requested placement without waiting for its own layout",
        ) {
            runTest {
                val state = mockk<LazyListState>()
                every { state.layoutInfo } returns layout("pinned", 0)
                every { state.firstVisibleItemIndex } returns 0
                every { state.firstVisibleItemScrollOffset } returns 0
                every { state.canScrollBackward } returns false
                every { state.isScrollInProgress } returns false
                justRun { state.requestScrollToItem(any(), any()) }
                val result = async(BroadcastFrameClock()) {
                    state.positionCreatedMemoForEnter(paging("created", rank = 4), "created", 4)
                }
                runCurrent()
                result.isCompleted shouldBe true
                result.await() shouldBe true
            }
        }
        test(
            "given paging has the committed head and the list is at top then entry does not wait for the previous layout to measure it",
        ) {
            runTest {
                val state = mockk<LazyListState>()
                every { state.layoutInfo } returns layout("previous", 0)
                every { state.canScrollBackward } returns false
                every { state.isScrollInProgress } returns false
                justRun { state.requestScrollToItem(any(), any()) }
                val result = async(BroadcastFrameClock()) {
                    state.positionCreatedMemoForEnter(paging("created"), "created", 0)
                }
                runCurrent()
                result.isCompleted shouldBe true
                result.await() shouldBe true
            }
        }
        test("given the exact held row already at top then entry acknowledgement needs no additional frame") {
            runTest {
                val state = mockk<LazyListState>()
                every { state.layoutInfo } returns layout("created", 0)
                every { state.canScrollBackward } returns false
                every { state.isScrollInProgress } returns false
                justRun { state.requestScrollToItem(any(), any()) }
                val result = async(BroadcastFrameClock()) {
                    state.positionCreatedMemoForEnter(paging("created"), "created", 0)
                }
                runCurrent()
                result.isCompleted shouldBe true
                result.await() shouldBe true
            }
        }
        test("given a viewport still below top then entry waits for its actual layout acknowledgement") {
            runTest {
                val ready = mutableStateOf(false)
                val state = mockk<LazyListState>()
                every { state.layoutInfo } answers { if (ready.value) layout("created", 0) else layout("old", 1) }
                every { state.canScrollBackward } answers { !ready.value }
                every { state.isScrollInProgress } returns false
                justRun { state.requestScrollToItem(any(), any()) }
                val result = async(BroadcastFrameClock()) {
                    state.positionCreatedMemoForEnter(paging("created"), "created", 0)
                }
                runCurrent()
                result.isCompleted shouldBe false
                ready.value = true
                Snapshot.sendApplyNotifications()
                runCurrent()
                result.isCompleted shouldBe true
                result.await() shouldBe true
            }
        }
        test("given a different paging row then a ready viewport must not reveal it as the committed identity") {
            runTest {
                val state = mockk<LazyListState>()
                every { state.layoutInfo } returns layout("created", 0)
                every { state.canScrollBackward } returns false
                every { state.isScrollInProgress } returns false
                justRun { state.requestScrollToItem(any(), any()) }
                val result = async(BroadcastFrameClock()) {
                    state.positionCreatedMemoForEnter(paging("other"), "created", 0)
                }
                runCurrent()
                result.isCompleted shouldBe true
                result.await() shouldBe false
            }
        }
    })
