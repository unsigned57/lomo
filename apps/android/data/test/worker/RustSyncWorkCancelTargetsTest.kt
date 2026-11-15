package com.lomo.data.worker

/*
 * Behavior Contract:
 * - Unit under test: RustSyncWorker.cancelTargets.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: cancelling remote sync drops periodic, oneshot, and deferred unique work together.
 *
 * Scenarios:
 * - Given the production unique-work names, when cancelTargets is listed, then periodic, oneshot,
 *   and deferred names are all present.
 *
 * Observable outcomes: exact unique-work name set.
 *
 * TDD proof:
 * - Target: ./kotlin test --include-module=data --include-classes='com.lomo.data.worker.RustSyncWorkCancelTargetsTest'
 * - RED: cancel() only cancelled WORK_NAME.
 *
 * Excludes: WorkManager enqueue / device execution.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for RustSyncWorkCancelTargets.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including cancel targets include periodic oneshot and
 * deferred unique work.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

class RustSyncWorkCancelTargetsTest :
    FunSpec({
        test("cancel targets include periodic oneshot and deferred unique work") {
            RustSyncWorker.cancelTargets().shouldContainExactlyInAnyOrder(
                RustSyncWorker.WORK_NAME,
                RustSyncWorker.ONESHOT_WORK_NAME,
                RustSyncWorker.DEFERRED_WORK_NAME,
            )
        }
    })
