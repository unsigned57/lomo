package com.lomo.app.feature.task

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.MainDispatcherExtension
import com.lomo.domain.model.MemoTask
import com.lomo.domain.repository.MemoTaskRepository
import com.lomo.domain.usecase.ListMemoTasksUseCase
import com.lomo.domain.usecase.ToggleMemoTaskUseCase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: TasksViewModel
 * - Owning layer: app
 * - Priority tier: P1
 * - Capability: load session task aggregation and toggle one line-index item, then refresh the list.
 *
 * Scenarios:
 * - Given repository tasks, when ensureLoaded runs, then Ready exposes those tasks.
 * - Given a visible open task, when toggled, then the repository records done=true and Ready
 *   reflects the refreshed list.
 *
 * Observable outcomes:
 * - TasksScreenState.Ready data and repository toggle arguments.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=app --include-classes='com.lomo.app.feature.task.TasksViewModelTest'
 * - RED before TasksViewModel exists because the app had no task aggregation entry.
 *
 * Excludes:
 * - Compose rendering, JNI, and Markdown rewrite internals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest : AppFunSpec() {
    private val testDispatcher = StandardTestDispatcher()

    init {
        extension(MainDispatcherExtension(testDispatcher))

        test("ensureLoaded exposes repository tasks") {
            runTest {
                val open =
                    MemoTask(
                        memoId = "m_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                        lineIndex = 0,
                        done = false,
                        text = "open",
                        sourcePath = "2026_09_10.md",
                    )
                val repository = RecordingMemoTaskRepository(tasks = listOf(open))
                val viewModel =
                    TasksViewModel(
                        listMemoTasksUseCase = ListMemoTasksUseCase(repository),
                        toggleMemoTaskUseCase = ToggleMemoTaskUseCase(repository),
                    )

                viewModel.ensureLoaded()
                advanceUntilIdle()

                val loaded = viewModel.uiState.value.shouldBeInstanceOf<TasksScreenState.Ready>()
                loaded.tasks shouldBe listOf(open)
            }
        }

        test("toggleTask writes done=true and refreshes the list") {
            runTest {
                val open =
                    MemoTask(
                        memoId = "m_ffffffffffffffffffffffffffffffff",
                        lineIndex = 1,
                        done = false,
                        text = "ship",
                        sourcePath = "2026_09_10.md",
                    )
                val done = open.copy(done = true)
                val repository = RecordingMemoTaskRepository(tasks = listOf(open))
                val viewModel =
                    TasksViewModel(
                        listMemoTasksUseCase = ListMemoTasksUseCase(repository),
                        toggleMemoTaskUseCase = ToggleMemoTaskUseCase(repository),
                    )
                viewModel.ensureLoaded()
                advanceUntilIdle()

                repository.tasks = listOf(done)
                viewModel.toggleTask(open)
                advanceUntilIdle()

                repository.toggles shouldBe listOf(open to true)
                (viewModel.uiState.value as TasksScreenState.Ready).tasks shouldBe listOf(done)
            }
        }
    }

    private class RecordingMemoTaskRepository(
        var tasks: List<MemoTask> = emptyList(),
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
