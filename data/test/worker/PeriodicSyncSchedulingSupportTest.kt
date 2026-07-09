package com.lomo.data.worker

import androidx.work.BackoffPolicy
import androidx.work.Data
import com.lomo.data.repository.S3_SYNC_WORK_INTENT_PARAMETER
import com.lomo.data.repository.S3SyncWorkIntent
import com.lomo.data.sync.SyncExistingWorkPolicy
import com.lomo.data.sync.SyncScheduledWork
import com.lomo.data.sync.SyncWorkBackoffPolicy
import com.lomo.data.sync.SyncWorkCadence
import com.lomo.data.sync.SyncWorkNetworkRequirement
import com.lomo.data.sync.SyncWorkPayload
import com.lomo.data.sync.SyncWorkRetryPolicy
import com.lomo.data.sync.SyncWorkTrigger
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.SyncBackendType
import io.kotest.matchers.shouldBe
import java.time.Duration

/*
 * Behavior Contract:
 * - Unit under test: central WorkManager sync scheduling support.
 * - Owning layer: data.
 * - Priority tier: P1.
 * - Capability: translate backend-neutral SyncScheduledWork retry/backoff policy into WorkManager request backoff criteria and worker retry-budget input data.
 *
 * Scenarios:
 * - Given policy-planned periodic work with retry/backoff, when the central periodic builder creates a WorkRequest, then WorkManager backoff policy, delay, constraints, input payload, and max-attempt budget match the policy output.
 * - Given policy-planned one-time catch-up work with retry/backoff, when the central one-time builder creates a WorkRequest, then WorkManager backoff policy, delay, and max-attempt budget match the policy output.
 *
 * Observable outcomes:
 * - WorkRequest WorkSpec backoffPolicy/backoffDelayDuration and input Data retry-attempt budget.
 *
 * TDD proof:
 * - RED: `:data:testDebugUnitTest --tests com.lomo.data.worker.PeriodicSyncSchedulingSupportTest` fails before the fix because scheduling builders only accept raw interval/constraints and never translate SyncScheduledWork retryPolicy.
 *
 * Excludes:
 * - WorkManager database enqueueing, provider sync execution, and Android scheduler timing.
 */
class PeriodicSyncSchedulingSupportTest : DataFunSpec() {
    init {
        test("given periodic policy work when request is built then backoff and retry budget are translated centrally") {
            val request =
                buildPeriodicSyncWorkRequest<S3SyncWorker>(
                    scheduledWork =
                        scheduledWork(
                            cadence = SyncWorkCadence.Periodic(Duration.ofHours(6)),
                            retryPolicy =
                                SyncWorkRetryPolicy(
                                    maxAttempts = 4,
                                    backoffPolicy = SyncWorkBackoffPolicy.Exponential,
                                    backoffDelay = Duration.ofMinutes(30),
                                ),
                        ),
                    inputData = S3SyncWorker.inputData(S3SyncWorkIntent.FULL_RECONCILE),
                )

            request.workSpec.backoffPolicy shouldBe BackoffPolicy.EXPONENTIAL
            request.workSpec.backoffDelayDuration shouldBe Duration.ofMinutes(30).toMillis()
            request.workSpec.input.getInt(SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY, -1) shouldBe 4
            request.workSpec.input.getString(S3_SYNC_WORK_INTENT_PARAMETER) shouldBe S3SyncWorkIntent.FULL_RECONCILE.name
        }

        test("given one-time policy work when request is built then backoff and retry budget are translated centrally") {
            val request =
                buildOneTimeSyncWorkRequest<S3SyncWorker>(
                    scheduledWork =
                        scheduledWork(
                            cadence = SyncWorkCadence.OneTime,
                            retryPolicy =
                                SyncWorkRetryPolicy(
                                    maxAttempts = 3,
                                    backoffPolicy = SyncWorkBackoffPolicy.Linear,
                                    backoffDelay = Duration.ofMinutes(15),
                                ),
                        ),
                    inputData = Data.EMPTY,
                )

            request.workSpec.backoffPolicy shouldBe BackoffPolicy.LINEAR
            request.workSpec.backoffDelayDuration shouldBe Duration.ofMinutes(15).toMillis()
            request.workSpec.input.getInt(SYNC_WORK_MAX_RETRY_ATTEMPTS_INPUT_KEY, -1) shouldBe 3
        }
    }

    private fun scheduledWork(
        cadence: SyncWorkCadence,
        retryPolicy: SyncWorkRetryPolicy,
    ): SyncScheduledWork =
        SyncScheduledWork(
            backend = SyncBackendType.S3,
            trigger = SyncWorkTrigger.PERIODIC_AUTO_SYNC,
            uniqueWorkName = S3SyncWorker.WORK_NAME,
            cadence = cadence,
            networkRequirement = SyncWorkNetworkRequirement.Connected,
            existingWorkPolicy = SyncExistingWorkPolicy.Replace,
            retryPolicy = retryPolicy,
            payload =
                SyncWorkPayload.ProviderParameters(
                    mapOf(S3_SYNC_WORK_INTENT_PARAMETER to S3SyncWorkIntent.FULL_RECONCILE.name),
                ),
        )
}
