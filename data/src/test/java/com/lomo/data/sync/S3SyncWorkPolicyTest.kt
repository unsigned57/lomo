package com.lomo.data.sync

import com.lomo.data.repository.S3EndpointProfile
import com.lomo.data.repository.S3RemoteShardState
import com.lomo.data.repository.S3_SYNC_WORK_INTENT_PARAMETER
import com.lomo.data.repository.S3SyncProtocolState
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.worker.S3SyncWorker
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.domain.model.SyncBackendType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

/*
 * Behavior Contract:
 * - Unit under test: S3 sync work policy through the backend-neutral SyncWorkPolicy contract.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: decide foreground refresh work and deferred WorkManager work from S3 endpoint profile, remote-index health, shard telemetry, trigger, network/cadence constraints, and retry/backoff budgets.
 *
 * Scenarios:
 * - Given auto-sync scheduling inputs, when S3 policy plans work, then fast periodic, full-reconcile periodic, optional catch-up work, and lane-specific retry/backoff budgets are emitted as backend-neutral scheduled work.
 * - Given refresh inputs, when S3 policy plans work, then foreground scan intensity and deferred catch-up retry/backoff work preserve first-open, stale-shard, high-uncertainty, and strong-refresh behavior.
 * - Given endpoint-specific shard pressure, when catch-up policy is evaluated, then endpoint profile thresholds change the selected catch-up work.
 *
 * Observable outcomes:
 * - foreground S3 scan payload, scheduled work cadence, unique work names, coalescing keys, network requirements, retry/backoff policy, and existing-work policy.
 *
 * TDD proof:
 * - RED: `:data:testDebugUnitTest --tests com.lomo.data.sync.S3SyncWorkPolicyTest` fails before the retry/backoff fix because SyncScheduledWork has no retryPolicy and S3 lanes cannot emit cost-aware retry budgets.
 *
 * Excludes:
 * - WorkManager request construction, S3 transport execution, conflict resolution, and UI refresh rendering.
 *
 * Test Change Justification:
 * - Reason category: S3 sync module gained remote object key policy, reconcile preparation, file bridge fingerprint ops, work telemetry, and streaming markdown; existing tests need updated assertions.
 * - Old behavior/assertion being replaced: previous sync tests relied on older file bridge, reconcile, and work policy contracts before these modules were added.
 * - Why old assertion is no longer correct: new modules introduce typed remote object key policy, reconcile preparation phases, and file bridge fingerprint verification that change the observable sync behavior.
 * - Coverage preserved by: all existing sync scenarios retained; new scenarios added for key policy, fingerprint ops, reconcile prep, and work telemetry.
 * - Why this is not fitting the test to the implementation: tests verify observable sync state transitions and file bridge outcomes, not internal implementation details.
 */
class S3SyncWorkPolicyTest : DataFunSpec() {
    init {
        test("auto schedule emits fast periodic reconcile periodic and full catch-up when no full scan exists") {
            val decision =
                policy.plan(
                    S3SyncWorkPolicyInput.AutoSchedule(
                        fastInterval = Duration.ofHours(6),
                        state =
                            state(
                                protocolState = S3SyncProtocolState(lastSuccessfulSyncAt = 10L),
                            ),
                    ),
                )

            decision.scheduledWork.map { it.uniqueWorkName } shouldContainExactly
                listOf(
                    S3SyncWorker.WORK_NAME,
                    S3SyncWorker.RECONCILE_WORK_NAME,
                    S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME,
                )
            assertSoftly(decision.scheduledWork[0]) {
                backend shouldBe SyncBackendType.S3
                trigger shouldBe SyncWorkTrigger.PERIODIC_AUTO_SYNC
                cadence shouldBe SyncWorkCadence.Periodic(Duration.ofHours(6))
                networkRequirement shouldBe SyncWorkNetworkRequirement.Connected
                existingWorkPolicy shouldBe SyncExistingWorkPolicy.Replace
                retryPolicy shouldBe
                    SyncWorkRetryPolicy(
                        maxAttempts = 2,
                        backoffPolicy = SyncWorkBackoffPolicy.Linear,
                        backoffDelay = Duration.ofMinutes(10),
                    )
                payload.s3Policy() shouldBe S3SyncWorkIntent.FAST_ONLY
                coalescingKey shouldBe SyncWorkCoalescingKey(SyncBackendType.S3, S3SyncWorker.WORK_NAME)
            }
            assertSoftly(decision.scheduledWork[1]) {
                cadence shouldBe SyncWorkCadence.Periodic(Duration.ofHours(6))
                networkRequirement shouldBe SyncWorkNetworkRequirement.UnmeteredCharging
                retryPolicy shouldBe
                    SyncWorkRetryPolicy(
                        maxAttempts = 4,
                        backoffPolicy = SyncWorkBackoffPolicy.Exponential,
                        backoffDelay = Duration.ofMinutes(30),
                    )
                payload.s3Policy() shouldBe S3SyncWorkIntent.FULL_RECONCILE
            }
            assertSoftly(decision.scheduledWork[2]) {
                trigger shouldBe SyncWorkTrigger.CATCH_UP
                cadence shouldBe SyncWorkCadence.OneTime
                networkRequirement shouldBe SyncWorkNetworkRequirement.UnmeteredCharging
                retryPolicy shouldBe
                    SyncWorkRetryPolicy(
                        maxAttempts = 4,
                        backoffPolicy = SyncWorkBackoffPolicy.Exponential,
                        backoffDelay = Duration.ofMinutes(30),
                    )
                payload.s3Policy() shouldBe S3SyncWorkIntent.FULL_RECONCILE
                coalescingKey shouldBe
                    SyncWorkCoalescingKey(SyncBackendType.S3, S3SyncWorker.RECONCILE_CATCH_UP_WORK_NAME)
            }
        }

        test("auto schedule applies endpoint profile to S3 reconcile retry backoff") {
            val decision =
                policy.plan(
                    S3SyncWorkPolicyInput.AutoSchedule(
                        fastInterval = Duration.ofHours(6),
                        state =
                            state(
                                protocolState = freshProtocolState(),
                                endpointProfile = S3EndpointProfile.MINIO_COMPAT,
                            ),
                    ),
                )

            val reconcileWork = decision.scheduledWork.single { it.uniqueWorkName == S3SyncWorker.RECONCILE_WORK_NAME }
            reconcileWork.retryPolicy shouldBe
                SyncWorkRetryPolicy(
                    maxAttempts = 3,
                    backoffPolicy = SyncWorkBackoffPolicy.Exponential,
                    backoffDelay = Duration.ofMinutes(45),
                )
        }

        test("effective reconcile interval uses endpoint profile floor") {
            effectiveS3ReconcileInterval(
                requestedInterval = Duration.ofHours(1),
                endpointProfile = S3EndpointProfile.AWS_S3,
            ) shouldBe Duration.ofHours(4)
            effectiveS3ReconcileInterval(
                requestedInterval = Duration.ofHours(6),
                endpointProfile = S3EndpointProfile.MINIO_COMPAT,
            ) shouldBe Duration.ofHours(12)
        }

        test("catch-up policy treats moderate change pressure differently by endpoint profile") {
            val shardState =
                S3RemoteShardState(
                    bucketId = "memo",
                    relativePrefix = "lomo/memo",
                    lastScannedAt = Duration.ofHours(23).toMillis(),
                    lastObjectCount = 10,
                    lastDurationMs = 50L,
                    lastChangeCount = 4,
                    idleScanStreak = 0,
                    lastVerificationAttemptCount = 2,
                    lastVerificationFailureCount = 0,
                )

            resolveCatchUpPolicy(
                state(
                    protocolState = freshProtocolState(),
                    shardStates = listOf(shardState),
                    endpointProfile = S3EndpointProfile.AWS_S3,
                ),
            ) shouldBe S3SyncWorkIntent.FAST_THEN_RECONCILE
            resolveCatchUpPolicy(
                state(
                    protocolState = freshProtocolState(),
                    shardStates = listOf(shardState),
                    endpointProfile = S3EndpointProfile.MINIO_COMPAT,
                ),
            ).shouldBeNull()
        }

        test("catch-up policy continues rolling reconcile when shard cursor is pending") {
            resolveCatchUpPolicy(
                state(
                    protocolState =
                        S3SyncProtocolState(
                            lastSuccessfulSyncAt = 10L,
                            lastFullRemoteScanAt = 500L,
                            remoteScanCursor = "memo:a",
                        ),
                    now = 1_000L,
                ),
            ) shouldBe S3SyncWorkIntent.FAST_THEN_RECONCILE
        }

        test("catch-up policy stays idle when recent deep scan is still fresh") {
            resolveCatchUpPolicy(state(protocolState = freshProtocolState())).shouldBeNull()
        }

        test("catch-up policy requests rolling reconcile when a shard is stale even if full scan is recent") {
            resolveCatchUpPolicy(
                state(
                    protocolState = freshProtocolState(),
                    shardStates =
                        listOf(
                            S3RemoteShardState(
                                bucketId = "memo",
                                relativePrefix = "lomo/memo",
                                lastScannedAt = 1L,
                                lastObjectCount = 10,
                                lastDurationMs = 50L,
                                lastChangeCount = 0,
                            ),
                        ),
                ),
            ) shouldBe S3SyncWorkIntent.FAST_THEN_RECONCILE
        }

        test("catch-up policy requests full reconcile when shard state is missing") {
            resolveCatchUpPolicy(
                state(
                    protocolState = freshProtocolState(),
                    shardStates = emptyList(),
                ),
            ) shouldBe S3SyncWorkIntent.FULL_RECONCILE
        }

        test("catch-up policy requests rolling reconcile when shard change rate spikes despite recent deep scan") {
            resolveCatchUpPolicy(
                state(
                    protocolState = freshProtocolState(),
                    shardStates =
                        listOf(
                            S3RemoteShardState(
                                bucketId = "memo",
                                relativePrefix = "lomo/memo",
                                lastScannedAt = Duration.ofHours(23).toMillis(),
                                lastObjectCount = 10,
                                lastDurationMs = 50L,
                                lastChangeCount = 6,
                                idleScanStreak = 0,
                                lastVerificationAttemptCount = 2,
                                lastVerificationFailureCount = 0,
                            ),
                        ),
                ),
            ) shouldBe S3SyncWorkIntent.FAST_THEN_RECONCILE
        }

        test("catch-up policy escalates to full reconcile when shard verification failures imply high uncertainty") {
            resolveCatchUpPolicy(
                state(
                    protocolState = freshProtocolState(),
                    shardStates = listOf(highUncertaintyShard()),
                ),
            ) shouldBe S3SyncWorkIntent.FULL_RECONCILE
        }

        test("refresh plan prefers fast foreground sync and full reconcile catch-up on first opening") {
            val decision =
                policy.plan(
                    S3SyncWorkPolicyInput.Refresh(
                        state =
                            state(
                                protocolState = S3SyncProtocolState(lastSuccessfulSyncAt = 10L),
                                now = 1_000L,
                            ),
                    ),
                )

            decision.foregroundWork?.payload?.s3Policy() shouldBe S3SyncWorkIntent.FAST_ONLY
            decision.scheduledWork.single().payload.s3Policy() shouldBe S3SyncWorkIntent.FULL_RECONCILE
        }

        test("refresh plan escalates foreground sync to full reconcile under high verification uncertainty") {
            val decision =
                policy.plan(
                    S3SyncWorkPolicyInput.Refresh(
                        state =
                            state(
                                protocolState = freshProtocolState(),
                                shardStates = listOf(highUncertaintyShard()),
                            ),
                    ),
                )

            decision.foregroundWork?.payload?.s3Policy() shouldBe S3SyncWorkIntent.FULL_RECONCILE
            decision.scheduledWork shouldBe emptyList()
        }

        test("refresh plan keeps foreground fast and schedules rolling catch-up when cold shards need coverage") {
            val decision =
                policy.plan(
                    S3SyncWorkPolicyInput.Refresh(
                        state =
                            state(
                                protocolState = freshProtocolState(),
                                shardStates =
                                    listOf(
                                        S3RemoteShardState(
                                            bucketId = "memo",
                                            relativePrefix = "lomo/memo",
                                            lastScannedAt = 1L,
                                            lastObjectCount = 10,
                                            lastDurationMs = 50L,
                                            lastChangeCount = 0,
                                        ),
                                    ),
                            ),
                    ),
                )

            decision.foregroundWork?.payload?.s3Policy() shouldBe S3SyncWorkIntent.FAST_ONLY
            decision.scheduledWork.single().payload.s3Policy() shouldBe S3SyncWorkIntent.FAST_THEN_RECONCILE
        }

        test("strong refresh signal upgrades otherwise fast foreground sync to fast then reconcile") {
            val decision =
                policy.plan(
                    S3SyncWorkPolicyInput.Refresh(
                        signal = SyncRefreshSignal.STRONG_REMOTE_HINT,
                        state = state(protocolState = freshProtocolState()),
                    ),
                )

            decision.foregroundWork?.payload?.s3Policy() shouldBe S3SyncWorkIntent.FAST_THEN_RECONCILE
            decision.scheduledWork.shouldBeEmpty()
        }

        test("strong refresh signal preserves existing full reconcile escalation") {
            val decision =
                policy.plan(
                    S3SyncWorkPolicyInput.Refresh(
                        signal = SyncRefreshSignal.STRONG_REMOTE_HINT,
                        state =
                            state(
                                protocolState = freshProtocolState(),
                                shardStates = listOf(highUncertaintyShard()),
                            ),
                    ),
                )

            decision.foregroundWork?.payload?.s3Policy() shouldBe S3SyncWorkIntent.FULL_RECONCILE
            decision.scheduledWork.shouldBeEmpty()
        }

        test("strong refresh signal keeps catch-up scheduling while upgrading foreground sync") {
            val decision =
                policy.plan(
                    S3SyncWorkPolicyInput.Refresh(
                        signal = SyncRefreshSignal.STRONG_REMOTE_HINT,
                        state =
                            state(
                                protocolState = freshProtocolState(),
                                shardStates =
                                    listOf(
                                        S3RemoteShardState(
                                            bucketId = "memo",
                                            relativePrefix = "lomo/memo",
                                            lastScannedAt = 1L,
                                            lastObjectCount = 10,
                                            lastDurationMs = 50L,
                                            lastChangeCount = 0,
                                        ),
                                    ),
                            ),
                    ),
                )

            decision.foregroundWork?.payload?.s3Policy() shouldBe S3SyncWorkIntent.FAST_THEN_RECONCILE
            decision.scheduledWork.single().payload.s3Policy() shouldBe S3SyncWorkIntent.FAST_THEN_RECONCILE
        }
    }

    private val policy = S3SyncWorkPolicy()

    private fun state(
        protocolState: S3SyncProtocolState?,
        reconcileInterval: Duration = Duration.ofHours(6),
        endpointProfile: S3EndpointProfile = S3EndpointProfile.GENERIC_S3,
        incrementalEnabled: Boolean = true,
        shardStates: List<S3RemoteShardState>? = null,
        now: Long = Duration.ofHours(24).toMillis(),
    ): S3SyncWorkState =
        S3SyncWorkState(
            reconcileInterval = reconcileInterval,
            endpointProfile = endpointProfile,
            incrementalEnabled = incrementalEnabled,
            protocolState = protocolState,
            shardStates = shardStates,
            now = now,
        )

    private fun freshProtocolState(): S3SyncProtocolState =
        S3SyncProtocolState(
            lastSuccessfulSyncAt = 10L,
            lastReconcileAt = Duration.ofHours(23).toMillis(),
            lastFullRemoteScanAt = Duration.ofHours(23).toMillis(),
        )

    private fun highUncertaintyShard(): S3RemoteShardState =
        S3RemoteShardState(
            bucketId = "memo",
            relativePrefix = "lomo/memo",
            lastScannedAt = Duration.ofHours(23).toMillis(),
            lastObjectCount = 10,
            lastDurationMs = 50L,
            lastChangeCount = 1,
            idleScanStreak = 0,
            lastVerificationAttemptCount = 4,
            lastVerificationFailureCount = 3,
        )

    private fun SyncWorkPayload.s3Policy(): S3SyncWorkIntent =
        shouldBeInstanceOf<SyncWorkPayload.ProviderParameters>()
            .values[S3_SYNC_WORK_INTENT_PARAMETER]
            ?.let(S3SyncWorkIntent::valueOf)
            ?: error("S3 policy payload missing work intent")
}
