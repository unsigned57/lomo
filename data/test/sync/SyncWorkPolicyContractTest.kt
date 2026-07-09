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
 * - Capability: model sync scheduling as policy decisions that repositories execute and WorkManager enqueues, including retry/backoff budget ownership.
 *
 * Scenarios:
 * - Given a remote auto-sync backend such as WebDAV, when auto-sync policy is planned, then one replace-coalesced periodic foreground-capable work item is emitted with connected-network constraints and policy-owned retry/backoff.
 * - Given a policy decision with several scheduled works, when coalescing keys are read, then backend and work name make each WorkManager lane unique.
 *
 * Observable outcomes:
 * - returned sync work decision, unique work name, coalescing key, interval, network requirement, retry/backoff policy, and existing-work policy.
 *
 * TDD proof:
 * - RED: `:data:testDebugUnitTest --tests com.lomo.data.sync.SyncWorkPolicyContractTest` fails before the retry/backoff fix because `SyncScheduledWork` does not expose retry/backoff policy.
 *
 * Excludes:
 * - Android WorkManager request construction, provider transport execution, and S3 remote-index heuristics.
 *
 * Test Change Justification:
 * - Reason category: S3 sync module gained remote object key policy, reconcile preparation, file bridge fingerprint ops, work telemetry, and streaming markdown; existing tests need updated assertions.
 * - Old behavior/assertion being replaced: previous sync tests relied on older file bridge, reconcile, and work policy contracts before these modules were added.
 * - Why old assertion is no longer correct: new modules introduce typed remote object key policy, reconcile preparation phases, and file bridge fingerprint verification that change the observable sync behavior.
 * - Coverage preserved by: all existing sync scenarios retained; new scenarios added for key policy, fingerprint ops, reconcile prep, and work telemetry.
 * - Why this is not fitting the test to the implementation: tests verify observable sync state transitions and file bridge outcomes, not internal implementation details.
 */
class SyncWorkPolicyContractTest : DataFunSpec() {
    init {
        test("given remote auto sync backend when auto sync is planned then policy emits one replace-coalesced connected work") {
            val policy =
                RemoteAutoSyncWorkPolicy(
                    backend = SyncBackendType.WEBDAV,
                    uniqueWorkName = "com.lomo.data.worker.WebDavSyncWorker",
                    workPayload = SyncWorkPayload.StandardRemoteSync,
                    retryPolicy =
                        SyncWorkRetryPolicy(
                            maxAttempts = 3,
                            backoffPolicy = SyncWorkBackoffPolicy.Exponential,
                            backoffDelay = Duration.ofMinutes(15),
                        ),
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
                        retryPolicy =
                            SyncWorkRetryPolicy(
                                maxAttempts = 3,
                                backoffPolicy = SyncWorkBackoffPolicy.Exponential,
                                backoffDelay = Duration.ofMinutes(15),
                            ),
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
