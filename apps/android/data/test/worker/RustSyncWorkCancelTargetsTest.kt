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
 */

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

class RustSyncWorkCancelTargetsTest : FunSpec({
    test("cancel targets include periodic oneshot and deferred unique work") {
        RustSyncWorker.cancelTargets().shouldContainExactlyInAnyOrder(
            RustSyncWorker.WORK_NAME,
            RustSyncWorker.ONESHOT_WORK_NAME,
            RustSyncWorker.DEFERRED_WORK_NAME,
        )
    }
})
