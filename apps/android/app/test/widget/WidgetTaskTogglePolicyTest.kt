/*
 * Behavior Contract:
 * - Unit under test: executeWidgetTaskToggle & resolveWidgetTaskListPresentation
 * - Owning layer: app
 * - Priority tier: P0
 * - Capability: enforce app-lock and mount admission gates before toggling tasks from home-screen
 *   widgets, verify the live line identity still matches before writing, and cleanly degrade to
 *   opening the owning memo when locked, unmounted, or out of date.
 *
 * Scenarios:
 * - Given an unlocked and mounted workspace with matching live task, when toggled, then
 *   toggleTask is invoked with targetDone and Toggled outcome is returned.
 * - Given a locked session, when toggled, then zero writes happen and OpenMemoFallback returns.
 * - Given an unmounted workspace, when toggled, then listTasks is not waited on and
 *   OpenMemoFallback returns.
 * - Given stale lineIndex or missing memo, when toggled, then no wrong line is toggled and
 *   OpenMemoFallback returns.
 * - Given a cold start settling through Ready + Revalidating before Verified, when toggled, then
 *   awaitWidgetMountWritable waits for write admission before invoking write-leased toggleTask.
 * - Given a settled non-writable mount (ReadOnlyRecovery / AwaitingWorkspaceSelection), when
 *   awaited, then awaitWidgetMountWritable returns promptly without waiting for timeout.
 *
 * Observable outcomes: WidgetTaskToggleOutcome variant and toggleTask invocation arguments.
 * TDD proof:
 * - RED before WidgetTaskToggleAction & executeWidgetTaskToggle exist.
 * - RED when executeWidgetTaskToggle/ToggleWidgetTaskAction gated on admitsProjectionReads and
 *   Opening only instead of write admission (Verified + unlocked lease).
 * Excludes: Glance ActionCallbackBroadcastReceiver platform wiring and native FFI writes.
 *
 * Test Change Justification:
 * - Reason category: write-mutation gate parameter rename in executeWidgetTaskToggle.
 * - Old behavior/assertion being replaced: existing scenarios passed `admitsProjectionReads` to
 *   `executeWidgetTaskToggle`.
 * - Why old assertion is no longer correct: toggling a task mutates Markdown and SQLite under
 *   WorkspaceMutationLease, which blocks writes during ProjectionFreshness.Revalidating even when
 *   projection reads are admitted.
 * - Coverage preserved by: all five prior scenarios remain intact under `admitsWrites`, plus new
 *   tests covering Revalidating -> Verified settling and terminal non-writable readiness.
 * - Why this is not fitting the test to the implementation: aligning the policy helper parameter with
 *   the domain write lease law eliminates the exact cold-start failure.
 */
package com.lomo.app.widget

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeEngineReadinessRepository
import com.lomo.app.testing.fakes.FakeWorkspaceMutationLease
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.MemoTask
import com.lomo.domain.model.ProjectionFreshness
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class WidgetTaskTogglePolicyTest : AppFunSpec() {
    init {
        test("unlocked admitted mount with live matching task executes toggle and returns Toggled") {
            runTest {
                var toggledArgs: Pair<MemoTask, Boolean>? = null
                val liveTask = MemoTask("memo-7", 3, false, "Buy filters", "2026-11-14.md")

                val outcome =
                    executeWidgetTaskToggle(
                        memoId = "memo-7",
                        lineIndex = 3,
                        targetDone = true,
                        isLocked = { false },
                        admitsWrites = { true },
                        listTasks = { listOf(liveTask) },
                        toggleTask = { task, done -> toggledArgs = task to done },
                    )

                outcome shouldBe WidgetTaskToggleOutcome.Toggled
                toggledArgs shouldBe (liveTask to true)
            }
        }

        test("locked session blocks write and falls back to opening the owning memo") {
            runTest {
                var listed = false
                var toggled = false

                val outcome =
                    executeWidgetTaskToggle(
                        memoId = "memo-7",
                        lineIndex = 3,
                        targetDone = true,
                        isLocked = { true },
                        admitsWrites = { true },
                        listTasks = {
                            listed = true
                            emptyList()
                        },
                        toggleTask = { _, _ -> toggled = true },
                    )

                outcome shouldBe WidgetTaskToggleOutcome.OpenMemoFallback("memo-7")
                listed shouldBe false
                toggled shouldBe false
            }
        }

        test("unmounted workspace returns OpenMemoFallback without suspending on listTasks") {
            runTest {
                var listed = false
                val outcome =
                    executeWidgetTaskToggle(
                        memoId = "memo-7",
                        lineIndex = 3,
                        targetDone = true,
                        isLocked = { false },
                        admitsWrites = { false },
                        listTasks = {
                            listed = true
                            emptyList()
                        },
                        toggleTask = { _, _ -> error("must not toggle when unmounted") },
                    )

                outcome shouldBe WidgetTaskToggleOutcome.OpenMemoFallback("memo-7")
                listed shouldBe false
            }
        }

        test("stale task line index prevents mutating another task and falls back to memo") {
            runTest {
                var toggled = false
                val shiftedTask = MemoTask("memo-7", 4, false, "Buy filters", "2026-11-14.md")

                val outcome =
                    executeWidgetTaskToggle(
                        memoId = "memo-7",
                        lineIndex = 3,
                        targetDone = true,
                        isLocked = { false },
                        admitsWrites = { true },
                        listTasks = { listOf(shiftedTask) },
                        toggleTask = { _, _ -> toggled = true },
                    )

                outcome shouldBe WidgetTaskToggleOutcome.OpenMemoFallback("memo-7")
                toggled shouldBe false
            }
        }

        test("cold background toggle settles Opening mount before checking admission and executes toggleTask") {
            runTest {
                var mounted = false
                var ensured = 0
                var toggledArgs: Pair<MemoTask, Boolean>? = null
                val liveTask = MemoTask("memo-9", 1, false, "Cold task", "2026-11-14.md")

                val outcome =
                    executeWidgetTaskToggle(
                        memoId = "memo-9",
                        lineIndex = 1,
                        targetDone = true,
                        isLocked = { false },
                        ensureMountReady = {
                            ensured += 1
                            mounted = true
                        },
                        admitsWrites = { mounted },
                        listTasks = { listOf(liveTask) },
                        toggleTask = { task, done -> toggledArgs = task to done },
                    )

                ensured shouldBe 1
                outcome shouldBe WidgetTaskToggleOutcome.Toggled
                toggledArgs shouldBe (liveTask to true)
            }
        }

        test("cold restore waits past Ready Revalidating until Verified write lease opens") {
            runTest {
                val engineReadiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)
                engineReadiness.publishProjectionFreshness(ProjectionFreshness.Unavailable)
                val writeLease = FakeWorkspaceMutationLease(engineReadiness)
                val liveTask = MemoTask("memo-12", 2, false, "Revalidated task", "2026-11-15.md")
                var toggledArgs: Pair<MemoTask, Boolean>? = null

                val lifecycle =
                    launch {
                        delay(20)
                        // Cold restore first publishes read-only retained projection.
                        engineReadiness.publishProjectionFreshness(ProjectionFreshness.Revalidating(0uL))
                        engineReadiness.publish(EngineReadiness.Ready)
                        delay(40)
                        // Revalidation completes and admits writes.
                        engineReadiness.publishProjectionFreshness(ProjectionFreshness.Verified(0uL))
                    }

                val outcome =
                    executeWidgetTaskToggle(
                        memoId = "memo-12",
                        lineIndex = 2,
                        targetDone = true,
                        isLocked = { false },
                        ensureMountReady = {
                            awaitWidgetMountWritable(
                                engineReadiness = engineReadiness,
                                writeLease = writeLease,
                                timeoutMs = 2_000L,
                            )
                        },
                        admitsWrites = {
                            engineReadiness.mount.value.admitsProjectionWrites && writeLease.isWritable()
                        },
                        listTasks = { listOf(liveTask) },
                        toggleTask = { task, done ->
                            writeLease.withWrite {
                                toggledArgs = task to done
                            }
                        },
                    )
                lifecycle.join()

                engineReadiness.engineStartRequests shouldBe 1
                outcome shouldBe WidgetTaskToggleOutcome.Toggled
                toggledArgs shouldBe (liveTask to true)
            }
        }

        test("awaitWidgetMountWritable exits promptly when readiness settles in ReadOnlyRecovery") {
            runTest {
                val engineReadiness = FakeEngineReadinessRepository(initial = EngineReadiness.Opening)
                engineReadiness.publishProjectionFreshness(ProjectionFreshness.Unavailable)
                val writeLease = FakeWorkspaceMutationLease(engineReadiness)
                val liveTask = MemoTask("memo-13", 0, false, "Recovery task", "2026-11-15.md")
                var listed = false

                val lifecycle =
                    launch {
                        delay(15)
                        engineReadiness.clearAuthority()
                        engineReadiness.publish(
                            EngineReadiness.ReadOnlyRecovery(
                                category = EngineFailureCategory.CORRUPTION,
                                code = "workspace_locked",
                                retryDisposition = EngineRetryDisposition.NEVER,
                                diagnostic = "locked",
                            ),
                        )
                    }

                val outcome =
                    executeWidgetTaskToggle(
                        memoId = "memo-13",
                        lineIndex = 0,
                        targetDone = true,
                        isLocked = { false },
                        ensureMountReady = {
                            awaitWidgetMountWritable(
                                engineReadiness = engineReadiness,
                                writeLease = writeLease,
                                timeoutMs = 10_000L,
                            )
                        },
                        admitsWrites = {
                            engineReadiness.mount.value.admitsProjectionWrites && writeLease.isWritable()
                        },
                        listTasks = {
                            listed = true
                            listOf(liveTask)
                        },
                        toggleTask = { _, _ -> error("must not write in recovery") },
                    )
                lifecycle.join()

                engineReadiness.engineStartRequests shouldBe 1
                listed shouldBe false
                outcome shouldBe WidgetTaskToggleOutcome.OpenMemoFallback("memo-13")
            }
        }

        test("stuck Revalidating mount falls back cleanly after bounded timeout without invoking toggleTask") {
            runTest {
                val engineReadiness = FakeEngineReadinessRepository(initial = EngineReadiness.Ready)
                engineReadiness.publishProjectionFreshness(ProjectionFreshness.Revalidating(0uL))
                val writeLease = FakeWorkspaceMutationLease(engineReadiness)
                val liveTask = MemoTask("memo-14", 0, false, "Stuck revalidating", "2026-11-15.md")
                var listed = false

                val outcome =
                    executeWidgetTaskToggle(
                        memoId = "memo-14",
                        lineIndex = 0,
                        targetDone = true,
                        isLocked = { false },
                        ensureMountReady = {
                            awaitWidgetMountWritable(
                                engineReadiness = engineReadiness,
                                writeLease = writeLease,
                                timeoutMs = 40L,
                            )
                        },
                        admitsWrites = {
                            engineReadiness.mount.value.admitsProjectionWrites && writeLease.isWritable()
                        },
                        listTasks = {
                            listed = true
                            listOf(liveTask)
                        },
                        toggleTask = { _, _ -> error("must not toggle before Verified") },
                    )

                listed shouldBe false
                outcome shouldBe WidgetTaskToggleOutcome.OpenMemoFallback("memo-14")
            }
        }
    }
}
