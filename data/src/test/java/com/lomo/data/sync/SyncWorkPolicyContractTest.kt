package com.lomo.data.sync

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration

/*
 * Behavior Contract:
 * - Unit under test: backend-neutral sync work policy contract.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: model sync scheduling as policy decisions that repositories execute and WorkManager enqueues.
 *
 * Scenarios:
 * - Given a remote auto-sync backend such as WebDAV, when auto-sync policy is planned, then one replace-coalesced periodic foreground-capable work item is emitted with connected-network constraints.
 * - Given a policy decision with several scheduled works, when coalescing keys are read, then backend and work name make each WorkManager lane unique.
 *
 * Observable outcomes:
 * - returned sync work decision, unique work name, coalescing key, interval, network requirement, and existing-work policy.
 *
 * TDD proof:
 * - RED: `:data:testDebugUnitTest --tests com.lomo.data.sync.SyncWorkPolicyContractTest` failed to compile before the fix because `RemoteAutoSyncWorkPolicy` and `SyncWork*` contract types did not exist.
 *
 * Excludes:
 * - Android WorkManager request construction, provider transport execution, and S3 remote-index heuristics.
 */
class SyncWorkPolicyContractTest : DataFunSpec() {
    init {
        test("given remote auto sync backend when auto sync is planned then policy emits one replace-coalesced connected work") {
            val policy =
                RemoteAutoSyncWorkPolicy(
                    backend = SyncBackendType.WEBDAV,
                    uniqueWorkName = "com.lomo.data.worker.WebDavSyncWorker",
                    workPayload = SyncWorkPayload.StandardRemoteSync,
                )

            val decision =
                policy.plan(
                    RemoteAutoSyncWorkInput(
                        trigger = SyncWorkTrigger.PERIODIC_AUTO_SYNC,
                        requestedInterval = Duration.ofHours(6),
                    ),
                )

            decision.scheduledWork shouldContainExactly
                listOf(
                    SyncScheduledWork(
                        backend = SyncBackendType.WEBDAV,
                        trigger = SyncWorkTrigger.PERIODIC_AUTO_SYNC,
                        uniqueWorkName = "com.lomo.data.worker.WebDavSyncWorker",
                        cadence = SyncWorkCadence.Periodic(Duration.ofHours(6)),
                        networkRequirement = SyncWorkNetworkRequirement.Connected,
                        existingWorkPolicy = SyncExistingWorkPolicy.Replace,
                        payload = SyncWorkPayload.StandardRemoteSync,
                    ),
                )
            decision.scheduledWork.single().coalescingKey shouldBe
                SyncWorkCoalescingKey(
                    backend = SyncBackendType.WEBDAV,
                    uniqueWorkName = "com.lomo.data.worker.WebDavSyncWorker",
                )
        }
    }
}
