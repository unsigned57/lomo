package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.collections.immutable.persistentListOf

/*
 * Behavior Contract:
 * - Unit under test: memo jump navigation handler.
 * - Owning layer: app/feature/memo.
 * - Priority tier: P1.
 * - Capability: route jump commands from an app-owned typed memo selection.
 *
 * Scenarios:
 * - Given a memo selection from a non-main menu, when jump is requested, then main-list focus is requested
 *   before navigating back to the main list.
 *
 * Observable outcomes:
 * - Boolean handled result and ordered focus/navigation callbacks.
 *
 * TDD proof:
 * - RED: Fails before the boundary fix because jump extracted Memo through a shared ui-components payload accessor.
 *
 * Excludes:
 * - NavHost back-stack implementation, Compose rendering, and LazyList scroll physics.
 */
class MemoJumpNavigationHandlerTest : AppFunSpec() {
    init {
        test("jump requests focus before navigating to main list") {
            val calls = mutableListOf<String>()

            val handled =
                handleMemoJumpToMain(
                    selection =
                        memoMenuSelection(
                            memo = memo("memo-42"),
                            dateFormat = "yyyy-MM-dd",
                            timeFormat = "HH:mm",
                        ),
                    requestFocusMemo = { memoId -> calls += "focus:$memoId" },
                    navigateToMain = { calls += "navigate" },
                )

            ((handled)) shouldBe true
            (calls) shouldBe (listOf("focus:memo-42", "navigate"))
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
