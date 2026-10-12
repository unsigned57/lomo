package com.lomo.data.worker

/*
 * Behavior Contract:
 * - Unit under test: DeferredLockAuthorizedWorkResume.
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: when the session unlocks, WorkManager input deferred while the
 *   session was locked is enqueued through the sync scheduler — and the scheduler
 *   is only demanded at that event. The lazy edge keeps the security-session <->
 *   deferred-work DI subgraph acyclic without changing the resume semantics.
 *
 * Scenarios:
 * - Given a store holding pending input, when onSessionAllowsBackgroundWork fires,
 *   then the scheduler resolves and enqueueSaved receives the stored input.
 * - Given an empty store, when onSessionAllowsBackgroundWork fires, then the
 *   scheduler is never resolved.
 *
 * Observable outcomes: scheduler lazy-resolution flag and enqueueSaved calls.
 *
 * TDD proof:
 * - RED command: ./kotlin test --include-module=data
 *   --include-classes='com.lomo.data.worker.DeferredLockAuthorizedWorkResumeTest'
 * - RED symptom: the constructor demanded RustSyncScheduler eagerly, so the test
 *   could not encode "scheduler only resolves on a non-empty take()" as a lazy
 *   boundary.
 *
 * Excludes: WorkManager admission itself (instrumented boundary), storage IO.
 */

import androidx.work.workDataOf
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest

class DeferredLockAuthorizedWorkResumeTest : DataFunSpec() {
    init {
        test("given pending input when session allows background work then enqueueSaved receives it") {
            runTest {
                val pending = workDataOf("backend" to "git")
                val store = mockk<DeferredLockWorkStore>()
                every { store.take() } returns pending
                var schedulerResolved = false
                val scheduler = mockk<RustSyncScheduler>(relaxed = true)
                val resume =
                    DeferredLockAuthorizedWorkResume(
                        store = store,
                        scheduler =
                            lazy {
                                schedulerResolved = true
                                scheduler
                            },
                        scope = this,
                    )

                resume.onSessionAllowsBackgroundWork()
                testScheduler.advanceUntilIdle()

                schedulerResolved shouldBe true
                verify(exactly = 1) { scheduler.enqueueSaved(pending) }
            }
        }

        test("given empty store when session allows background work then scheduler is never resolved") {
            runTest {
                val store = mockk<DeferredLockWorkStore>()
                every { store.take() } returns null
                var schedulerResolved = false
                val scheduler = mockk<RustSyncScheduler>(relaxed = true)
                val resume =
                    DeferredLockAuthorizedWorkResume(
                        store = store,
                        scheduler =
                            lazy {
                                schedulerResolved = true
                                scheduler
                            },
                        scope = this,
                    )

                resume.onSessionAllowsBackgroundWork()
                testScheduler.advanceUntilIdle()

                schedulerResolved shouldBe false
                verify(exactly = 0) { scheduler.enqueueSaved(any()) }
            }
        }
    }
}
