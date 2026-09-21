package com.lomo.data.repository

import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.MemoTask
import com.lomo.nativebridge.SessionTaskItem
import com.lomo.nativebridge.SessionToggleTaskRequest
import com.lomo.nativebridge.StoreMemoCommit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: StoreMemoTaskRepository
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: list and toggle Markdown tasks through the application session. Projection
 *   publication of the toggle stamp is owned by the engine adapter, not this repository.
 *
 * Scenarios:
 * - Given session task items, when listTasks runs while Ready, then domain tasks map line index
 *   and text without calling a store write.
 * - Given the engine is not Ready, when listTasks runs, then the result is empty and session list
 *   is not called.
 * - Given a domain task, when toggleTask runs, then session toggle receives that memo id / line.
 *
 * Observable outcomes:
 * - Mapped MemoTask fields and session request fields.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.StoreMemoTaskRepositoryTest'
 * - RED before StoreMemoTaskRepository exists because Android had no session task repository.
 *
 * Test Change Justification:
 * - Reason category: T26 single publication exit.
 * - Old behavior/assertion being replaced: toggleTask asserted StoreInvalidationBus.coreRevision.
 * - Why old assertion is no longer correct: repositories must not publish owner stamps; the
 *   engine adapter observes sessionToggleTask receipts.
 * - Coverage preserved by: session request field assertions here and
 *   RustEngineAdapterTest toggle publication.
 * - Why this is not fitting the test to the implementation: locks the remaining repository
 *   duty (forward the frozen command) instead of re-testing the adapter bus.
 *
 * Excludes:
 * - JNI, Markdown rewrite internals, Compose UI, and adapter publication.
 */
class StoreMemoTaskRepositoryTest : FunSpec({
    test("listTasks maps session items while Ready") {
        runTest {
            val session =
                RecordingTaskSessionBridge(
                    tasks =
                        listOf(
                            SessionTaskItem(
                                memoId = "m_cccccccccccccccccccccccccccccccc",
                                lineIndex = 3u,
                                done = true,
                                text = "ship",
                                sourcePath = "2026_09_10.md",
                            ),
                        ),
                )
            val repository =
                StoreMemoTaskRepository(
                    session = session,
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    readiness = FakeEngineReadinessRepository(),
                )

            val tasks = repository.listTasks()

            tasks shouldBe
                listOf(
                    MemoTask(
                        memoId = "m_cccccccccccccccccccccccccccccccc",
                        lineIndex = 3,
                        done = true,
                        text = "ship",
                        sourcePath = "2026_09_10.md",
                    ),
                )
            session.listCallCount shouldBe 1
        }
    }

    test("listTasks is empty when the engine is not Ready") {
        runTest {
            val session = RecordingTaskSessionBridge()
            val readiness =
                FakeEngineReadinessRepository(initial = EngineReadiness.AwaitingWorkspaceSelection)
            val repository =
                StoreMemoTaskRepository(
                    session = session,
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    readiness = readiness,
                )

            repository.listTasks() shouldBe emptyList()
            session.listCallCount shouldBe 0
        }
    }

    test("toggleTask sends session line-index toggle") {
        runTest {
            val session = RecordingTaskSessionBridge()
            val repository =
                StoreMemoTaskRepository(
                    session = session,
                    writeLease = alwaysWritableWorkspaceMutationLease(),
                    readiness = FakeEngineReadinessRepository(),
                )
            val task =
                MemoTask(
                    memoId = "m_dddddddddddddddddddddddddddddddd",
                    lineIndex = 1,
                    done = false,
                    text = "review",
                    sourcePath = "2026_09_10.md",
                )

            repository.toggleTask(task, done = true)

            session.lastToggle?.memoId shouldBe task.memoId
            session.lastToggle?.lineIndex shouldBe 1u
            session.lastToggle?.done shouldBe true
        }
    }
})

private class RecordingTaskSessionBridge(
    private val tasks: List<SessionTaskItem> = emptyList(),
) : SessionNativeBridge {
    var listCallCount = 0
    var lastToggle: SessionToggleTaskRequest? = null

    override fun sessionListTasks(): List<SessionTaskItem> {
        listCallCount += 1
        return tasks
    }

    override fun sessionToggleTask(request: SessionToggleTaskRequest): StoreMemoCommit {
        lastToggle = request
        return StoreMemoCommit(
            operationId = request.operationId,
            memoId = request.memoId,
            coreRevision = 4uL,
            eventSequence = 5uL,
            contentRevision = 2uL,
            fileFingerprint = "fp",
            scopes = listOf(
                com.lomo.nativebridge.StoreInvalidationScope.MEMO_LIST,
                com.lomo.nativebridge.StoreInvalidationScope.SEARCH,
            ),
            idempotentReplay = false,
        )
    }
}
