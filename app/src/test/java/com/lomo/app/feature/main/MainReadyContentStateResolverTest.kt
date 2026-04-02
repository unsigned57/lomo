package com.lomo.app.feature.main

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/*
 * Test Contract:
 * - Unit under test: resolveMainReadyContentState
 * - Behavior focus: main-screen ready-state rendering while mapped ui memo models lag behind
 *   raw memo availability after a directory refresh.
 * - Observable outcomes: derived ready-content branch selection for loading, empty, and list.
 * - Red phase: Fails before the fix because ready-state rendering resolves to Empty when raw memos
 *   already exist but ui memo models are still empty, causing a visible empty-state flash.
 * - Excludes: Compose rendering, ViewModel wiring, refresh orchestration, and memo mapping internals.
 */
class MainReadyContentStateResolverTest {
    @Test
    fun `resolves loading while raw memos exist but ui memos are still empty`() {
        assertEquals(
            MainReadyContentState.Loading,
            resolveMainReadyContentState(
                hasRawItems = true,
                uiMemos = emptyList(),
            ),
        )
    }

    @Test
    fun `resolves empty when no raw memos are available`() {
        assertEquals(
            MainReadyContentState.Empty,
            resolveMainReadyContentState(
                hasRawItems = false,
                uiMemos = emptyList(),
            ),
        )
    }

    @Test
    fun `resolves list when mapped ui memos are available`() {
        assertEquals(
            MainReadyContentState.List,
            resolveMainReadyContentState(
                hasRawItems = true,
                uiMemos = listOf(memoUiModel("memo-1")),
            ),
        )
    }

    private fun memoUiModel(id: String): MemoUiModel =
        MemoUiModel(
            memo =
                com.lomo.domain.model.Memo(
                    id = id,
                    timestamp =
                        LocalDate.of(2026, 4, 1)
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli(),
                    content = id,
                    rawContent = id,
                    dateKey = "2026_04_01",
                    localDate = LocalDate.of(2026, 4, 1),
                ),
            processedContent = id,
            precomputedRenderPlan = null,
            tags = persistentListOf(),
        )
}
