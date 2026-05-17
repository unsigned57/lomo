package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import com.lomo.ui.component.menu.MemoMenuState
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.collections.immutable.persistentListOf

/*
 * Test Contract:
 * - Unit under test: memo jump navigation handler.
 * - Behavior focus: non-main memo menus must request main-list focus for the target memo and then navigate
 *   back to the main list using one shared action.
 * - Observable outcomes: boolean handled result and ordered focus/navigation callbacks.
 * - Red phase: Fails before the fix because Gallery, Search, Tag, and Review inline separate jump handlers,
 *   and there is no shared handler that guarantees focus is requested before main navigation.
 * - Excludes: NavHost back-stack implementation, Compose rendering, and LazyList scroll physics.
 */
class MemoJumpNavigationHandlerTest : AppFunSpec() {
    init {
        test("jump requests focus before navigating to main list") {
            val calls = mutableListOf<String>()

            val handled =
                handleMemoJumpToMain(
                    state = MemoMenuState.withPayload(memo("memo-42")),
                    requestFocusMemo = { memoId -> calls += "focus:$memoId" },
                    navigateToMain = { calls += "navigate" },
                )

            ((handled)) shouldBe true
            (calls) shouldBe (listOf("focus:memo-42", "navigate"))
        }
    }

    init {
        test("jump ignores menu states without memo payload") {
            val calls = mutableListOf<String>()

            val handled =
                handleMemoJumpToMain(
                    state = MemoMenuState(),
                    requestFocusMemo = { memoId -> calls += "focus:$memoId" },
                    navigateToMain = { calls += "navigate" },
                )

            ((handled)) shouldBe false
            (calls) shouldBe (emptyList<String>())
        }
    }

    private fun memo(id: String): Memo =
        Memo(
            id = id,
            timestamp =
                LocalDate
                    .of(2026, 5, 5)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
            content = id,
            rawContent = id,
            dateKey = "2026_05_05",
            localDate = LocalDate.of(2026, 5, 5),
            tags = persistentListOf(),
        )
}
