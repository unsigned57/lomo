package com.lomo.domain.usecase

import com.lomo.domain.model.MemoTask
import com.lomo.domain.repository.MemoTaskRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: ListMemoTasksUseCase, ToggleMemoTaskUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: expose session-owned task aggregation and line-index toggle without editor spans.
 *
 * Scenarios:
 * - Given repository tasks, when list is invoked, then those tasks are returned unchanged.
 * - Given a task and a done flag, when toggle is invoked, then the repository receives that pair.
 *
 * Observable outcomes:
 * - Returned MemoTask list and recorded toggle arguments.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=domain --include-classes='com.lomo.domain.usecase.MemoTaskUseCaseTest'
 * - RED before the use cases exist because domain had no session task entry.
 *
 * Excludes:
 * - Session FFI mapping, Markdown rewrite, and Compose rendering.
 */
class MemoTaskUseCaseTest : DomainFunSpec() {
    init {
        test("list returns repository tasks without reordering") {
            runTest {
                val expected =
                    listOf(
                        MemoTask(
                            memoId = "m_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            lineIndex = 2,
                            done = false,
                            text = "buy milk",
                            sourcePath = "2026_09_10.md",
                        ),
                    )
                val repository = RecordingMemoTaskRepository(tasks = expected)
                ListMemoTasksUseCase(repository)() shouldBe expected
            }
        }

        test("toggle forwards the addressed task and done flag") {
            runTest {
                val task =
                    MemoTask(
                        memoId = "m_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        lineIndex = 0,
                        done = false,
                        text = "write",
                        sourcePath = "2026_09_10.md",
                    )
                val repository = RecordingMemoTaskRepository()
                ToggleMemoTaskUseCase(repository)(task = task, done = true)
                repository.toggles shouldBe listOf(task to true)
            }
        }
    }

    private class RecordingMemoTaskRepository(
        private val tasks: List<MemoTask> = emptyList(),
    ) : MemoTaskRepository {
        val toggles = mutableListOf<Pair<MemoTask, Boolean>>()

        override suspend fun listTasks(): List<MemoTask> = tasks

        override suspend fun toggleTask(
            task: MemoTask,
            done: Boolean,
        ) {
            toggles += task to done
        }
    }
}
