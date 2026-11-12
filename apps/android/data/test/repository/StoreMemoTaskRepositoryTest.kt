package com.lomo.data.repository

import com.lomo.data.engine.SessionNativeBridge
import com.lomo.data.testing.fakes.FakeEngineReadinessRepository
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.MemoTask
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.nativebridge.SessionTaskItem
import com.lomo.nativebridge.SessionToggleTaskRequest
import com.lomo.nativebridge.StoreMemoCommit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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
 * - Given the mount is still Opening or Ready with an unverified first projection, when listTasks
 *   runs, then the call waits for the settled mount and returns real tasks instead of fabricating
 *   an empty list that the screen would latch.
 * - Given a domain task, when toggleTask runs, then session toggle receives that memo id / line.
 *
 * Observable outcomes:
 * - Mapped MemoTask fields and session request fields.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.repository.StoreMemoTaskRepositoryTest'
 * - RED before StoreMemoTaskRepository exists because Android had no session task repository.
 * - RED for the settled-mount wait: an Opening mount answered empty synchronously before the fix
 *   (the "No open tasks" regression); GREEN after listTasks suspends until the mount admits reads.
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
@OptIn(ExperimentalCoroutinesApi::class)
class StoreMemoTaskRepositoryTest :
    FunSpec({
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

        test("listTasks waits out an Opening mount instead of fabricating an empty list") {
            runTest {
                val item =
                    SessionTaskItem(
                        memoId = "m_a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1",
                        lineIndex = 0u,
                        done = false,
                        text = "queued",
                        sourcePath = "2026_09_11.md",
                    )
                val session = RecordingTaskSessionBridge(tasks = listOf(item))
                val readiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)
                val repository =
                    StoreMemoTaskRepository(
                        session = session,
                        writeLease = alwaysWritableWorkspaceMutationLease(),
                        readiness = readiness,
                    )

                val pending = backgroundScope.async { repository.listTasks() }
                runCurrent()
                pending.isActive shouldBe true
                session.listCallCount shouldBe 0

                readiness.publish(EngineReadiness.Ready)
                pending.await() shouldBe
                    listOf(
                        MemoTask(
                            memoId = item.memoId,
                            lineIndex = 0,
                            done = false,
                            text = "queued",
                            sourcePath = "2026_09_11.md",
                        ),
                    )
                session.listCallCount shouldBe 1
            }
        }

        test("listTasks waits for the first verified projection on a Ready mount") {
            runTest {
                val session =
                    RecordingTaskSessionBridge(
                        tasks =
                        listOf(
                            SessionTaskItem(
                                memoId = "m_b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2",
                                lineIndex = 2u,
                                done = false,
                                text = "indexed",
                                sourcePath = "2026_09_12.md",
                            ),
                        ),
                    )
                val readiness = FakeEngineReadinessRepository()
                readiness.publishProjectionFreshness(ProjectionFreshness.Unavailable)
                val repository =
                    StoreMemoTaskRepository(
                        session = session,
                        writeLease = alwaysWritableWorkspaceMutationLease(),
                        readiness = readiness,
                    )

                val pending = backgroundScope.async { repository.listTasks() }
                runCurrent()
                pending.isActive shouldBe true
                session.listCallCount shouldBe 0

                readiness.publishProjectionFreshness(ProjectionFreshness.Verified(0uL))
                (pending.await().single().text) shouldBe "indexed"
                session.listCallCount shouldBe 1
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

private class RecordingTaskSessionBridge(private val tasks: List<SessionTaskItem> = emptyList()) :
    SessionNativeBridge {
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
