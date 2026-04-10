package com.lomo.app.feature.main

import com.lomo.domain.model.Memo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: main-screen Jump focus request resolver.
 * - Behavior focus: Jump actions returning from Daily Review or Gallery must resolve to an immediate list-focus
 *   request for the matching visible memo instead of a long-distance animated scroll.
 * - Observable outcomes: returned focus request type and target index for hit and miss cases.
 * - Red phase: Fails before the fix because main-screen Jump handling still inlines a long-distance animated
 *   list scroll instead of resolving an immediate-focus request contract.
 * - Excludes: Compose rendering, NavHost back-stack transitions, and LazyListState scroll physics.
 */
class MainScreenFocusRequestResolverTest {
    @Test
    fun `returns immediate focus request when target memo is visible`() {
        val request =
            resolveMainScreenFocusRequest(
                memoId = "memo-2",
                visibleUiMemos =
                    listOf(
                        memoUiModel("memo-1"),
                        memoUiModel("memo-2"),
                        memoUiModel("memo-3"),
                    ).toImmutableList(),
            )

        assertEquals(MainScreenFocusRequest.Immediate(index = 1), request)
    }

    @Test
    fun `returns immediate focus request for the last visible memo`() {
        val request =
            resolveMainScreenFocusRequest(
                memoId = "memo-3",
                visibleUiMemos =
                    listOf(
                        memoUiModel("memo-1"),
                        memoUiModel("memo-2"),
                        memoUiModel("memo-3"),
                    ).toImmutableList(),
            )

        assertEquals(MainScreenFocusRequest.Immediate(index = 2), request)
    }

    @Test
    fun `returns not found when target memo is not visible`() {
        val request =
            resolveMainScreenFocusRequest(
                memoId = "missing",
                visibleUiMemos =
                    listOf(
                        memoUiModel("memo-1"),
                        memoUiModel("memo-2"),
                    ).toImmutableList(),
            )

        assertEquals(MainScreenFocusRequest.NotFound, request)
    }

    @Test
    fun `focuses matching memo with one immediate scroll`() =
        runTest {
            val scroller = RecordingFocusScroller()

            val handled =
                focusMemoInMainScreen(
                    memoId = "memo-2",
                    visibleUiMemos =
                        listOf(
                            memoUiModel("memo-1"),
                            memoUiModel("memo-2"),
                            memoUiModel("memo-3"),
                        ).toImmutableList(),
                    scroller = scroller,
                )

            assertTrue(handled)
            assertEquals(listOf(1), scroller.indexes)
        }

    @Test
    fun `does not scroll when target memo is absent`() =
        runTest {
            val scroller = RecordingFocusScroller()

            val handled =
                focusMemoInMainScreen(
                    memoId = "missing",
                    visibleUiMemos =
                        listOf(
                            memoUiModel("memo-1"),
                            memoUiModel("memo-2"),
                        ).toImmutableList(),
                    scroller = scroller,
                )

            assertEquals(false, handled)
            assertEquals(emptyList<Int>(), scroller.indexes)
        }

    private fun memoUiModel(id: String): MemoUiModel =
        MemoUiModel(
            memo =
                Memo(
                    id = id,
                    timestamp =
                        LocalDate.of(2026, 4, 10)
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli(),
                    content = id,
                    rawContent = id,
                    dateKey = "2026_04_10",
                    localDate = LocalDate.of(2026, 4, 10),
                ),
            processedContent = id,
            precomputedRenderPlan = null,
            tags = persistentListOf(),
        )

    private class RecordingFocusScroller : MainScreenFocusScroller {
        val indexes = mutableListOf<Int>()

        override suspend fun scrollToItem(index: Int) {
            indexes += index
        }
    }
}
