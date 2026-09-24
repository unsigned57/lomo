package com.lomo.app.feature.main

import com.lomo.domain.model.MemoListFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/*
 * Behavior Contract:
 * - Unit under test: resolveMainListFocusRetryKey
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: produce an equality-keyed retry discriminator for queued focus requests from the
 *   query epoch, the visible window bounds, and target visibility, without enumerating window rows.
 *
 * Scenarios:
 * - Given identical epoch, window bounds, and target visibility, when keys resolve, then they are
 *   equal.
 * - Given two windows that differ only in middle rows, when keys resolve, then they are equal
 *   because the key is bound to window bounds rather than row enumeration.
 * - Given the focus target enters the visible window, when keys resolve, then the key changes.
 * - Given the search query or structural filter changes, when keys resolve, then the epoch changes
 *   the key.
 *
 * Observable outcomes:
 * - MainListFocusRetryKey equality / inequality.
 *
 * TDD proof:
 * - Fails while the focus retry key enumerates every visible memo id, rebuilding an O(loaded) list
 *   on every composition pass.
 *
 * Excludes:
 * - Compose effect wiring and LazyListState scrolling.
 */
class MainListFocusRetryKeyTest : FunSpec({
    fun uiMemo(id: String): MemoUiModel =
        MemoUiModel(
            memo =
                com.lomo.domain.model.Memo(
                    id = id,
                    content = "content-$id",
                    rawContent = "content-$id",
                    timestamp = 1L,
                    dateKey = "2026_06_27",
                ),
            processedContent = "content-$id",
            renderDocument = com.lomo.app.testing.fakes.emptyRenderDocument(),
            tags = kotlinx.collections.immutable.persistentListOf(),
            reminders = kotlinx.collections.immutable.persistentListOf(),
            imageUrls = kotlinx.collections.immutable.persistentListOf(),
            shouldShowExpand = false,
            collapsedSummary = "",
        )

    test("identical epoch, window bounds, and target visibility produce equal keys") {
        val window = listOf(uiMemo("a"), uiMemo("b"), uiMemo("c"))

        resolveMainListFocusRetryKey(
            searchQuery = "design",
            filter = MemoListFilter(hasTodo = true),
            windowStartIndex = 10,
            visibleMemos = window,
            pendingFocusMemoIds = setOf("zzz"),
        ) shouldBe
            resolveMainListFocusRetryKey(
                searchQuery = "design",
                filter = MemoListFilter(hasTodo = true),
                windowStartIndex = 10,
                visibleMemos = window,
                pendingFocusMemoIds = setOf("zzz"),
            )
    }

    test("windows differing only in middle rows share one key because bounds do not enumerate rows") {
        val window = (1..20).map { uiMemo("m$it") }
        val swapped = window.toMutableList().apply { set(7, uiMemo("other")) }

        resolveMainListFocusRetryKey(
            searchQuery = "",
            filter = MemoListFilter(),
            windowStartIndex = 40,
            visibleMemos = window,
            pendingFocusMemoIds = emptySet(),
        ) shouldBe
            resolveMainListFocusRetryKey(
                searchQuery = "",
                filter = MemoListFilter(),
                windowStartIndex = 40,
                visibleMemos = swapped,
                pendingFocusMemoIds = emptySet(),
            )
    }

    test("a shifted window start changes the key") {
        val window = listOf(uiMemo("a"), uiMemo("b"))

        resolveMainListFocusRetryKey(
            searchQuery = "",
            filter = MemoListFilter(),
            windowStartIndex = 10,
            visibleMemos = window,
            pendingFocusMemoIds = emptySet(),
        ) shouldNotBe
            resolveMainListFocusRetryKey(
                searchQuery = "",
                filter = MemoListFilter(),
                windowStartIndex = 11,
                visibleMemos = window,
                pendingFocusMemoIds = emptySet(),
            )
    }

    test("the focus target entering the visible window flips the key") {
        val window = listOf(uiMemo("a"), uiMemo("b"), uiMemo("c"))

        resolveMainListFocusRetryKey(
            searchQuery = "",
            filter = MemoListFilter(),
            windowStartIndex = 0,
            visibleMemos = window,
            pendingFocusMemoIds = setOf("b"),
        ) shouldNotBe
            resolveMainListFocusRetryKey(
                searchQuery = "",
                filter = MemoListFilter(),
                windowStartIndex = 0,
                visibleMemos = window,
                pendingFocusMemoIds = setOf("zzz"),
            )
    }

    test("a search query change produces a different key") {
        val window = listOf(uiMemo("a"))

        resolveMainListFocusRetryKey(
            searchQuery = "design",
            filter = MemoListFilter(),
            windowStartIndex = 0,
            visibleMemos = window,
            pendingFocusMemoIds = emptySet(),
        ) shouldNotBe
            resolveMainListFocusRetryKey(
                searchQuery = "other",
                filter = MemoListFilter(),
                windowStartIndex = 0,
                visibleMemos = window,
                pendingFocusMemoIds = emptySet(),
            )
    }

    test("a structural filter change produces a different key") {
        val window = listOf(uiMemo("a"))

        resolveMainListFocusRetryKey(
            searchQuery = "",
            filter = MemoListFilter(hasTodo = true),
            windowStartIndex = 0,
            visibleMemos = window,
            pendingFocusMemoIds = emptySet(),
        ) shouldNotBe
            resolveMainListFocusRetryKey(
                searchQuery = "",
                filter = MemoListFilter(hasUrl = true),
                windowStartIndex = 0,
                visibleMemos = window,
                pendingFocusMemoIds = emptySet(),
            )
    }
})
