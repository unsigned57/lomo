package com.lomo.app.feature.memo

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeMemoQueryRepository
import com.lomo.app.testing.fakes.FakeMemoStore
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import com.lomo.ui.component.menu.MemoMenuState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: copy a complete memo by identity; owner: app/feature/memo; P1.
 * Scenarios:
 * - Given a long memo whose list row is truncated, when resolving its copy, then the full body,
 *   including its tail and supplementary Unicode characters, is returned.
 * - Given a missing memo, a preview, or a claimed full snapshot with the wrong character count,
 *   when resolving its copy, then the operation fails rather than returning partial text.
 * Observable outcomes: returned clipboard body or explicit failure.
 * TDD proof: RED replayed the original `selection.state.content` copy at this boundary using
 * `./kotlin test --include-module=app --include-classes='com.lomo.app.feature.memo.MemoCopyContentTest'`:
 * the full body assertion and incomplete snapshot rejection both failed. The same command verifies GREEN.
 * Excludes: clipboard service failure and screen-reader selection behavior.
 */
class MemoCopyContentTest : AppFunSpec() {
    init {
        test("given a long memo when copying its identity then the complete body is returned") {
            runTest {
                val body = "begin" + "内容".repeat(150) + "📝end"
                val repository = FakeMemoQueryRepository(FakeMemoStore(initialActive = listOf(memo(body))))
                val selection = selection(memo(body.take(100)).copy(contentKind = MemoContentKind.Preview))

                loadMemoContentForCopy(selection, repository) shouldBe body
            }
        }

        test("given a missing or incomplete snapshot when copying then no partial body is returned") {
            runTest {
                val preview = memo("truncated").copy(contentKind = MemoContentKind.Preview)
                val forged = memo("truncated").copy(projectedCharCount = 99)
                listOf(emptyList(), listOf(preview), listOf(forged)).forEach { memos ->
                    val repository = FakeMemoQueryRepository(FakeMemoStore(initialActive = memos))
                    shouldThrow<IllegalStateException> { loadMemoContentForCopy(selection(preview), repository) }
                }
            }
        }
    }
}

private fun memo(body: String) = Memo(
    id = "memo-1",
    timestamp = 1L,
    content = body,
    rawContent = body,
    dateKey = "2026_10_27",
    projectedCharCount = body.length.toLong(),
)

private fun selection(memo: Memo) = MemoMenuSelection(
    memo = memo,
    state = MemoMenuState(content = memo.content),
)
