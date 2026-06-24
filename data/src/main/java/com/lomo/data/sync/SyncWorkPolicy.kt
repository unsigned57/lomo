package com.lomo.data.sync

import com.lomo.data.worker.GitSyncWorker
import com.lomo.data.worker.WebDavSyncWorker
import com.lomo.domain.model.SyncBackendType
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

interface SyncWorkPolicy<Input> {
    fun plan(input: Input): SyncWorkDecision
}

data class SyncWorkDecision(
    val foregroundWork: SyncForegroundWork? = null,
    val scheduledWork: List<SyncScheduledWork> = emptyList(),
)

data class SyncForegroundWork(
    val backend: SyncBackendType,
    val trigger: SyncWorkTrigger,
    val payload: SyncWorkPayload,
)

data class SyncScheduledWork(
    val backend: SyncBackendType,
    val trigger: SyncWorkTrigger,
    val uniqueWorkName: String,
    val cadence: SyncWorkCadence,
    val networkRequirement: SyncWorkNetworkRequirement,
    val existingWorkPolicy: SyncExistingWorkPolicy,
    val retryPolicy: SyncWorkRetryPolicy,
    val payload: SyncWorkPayload,
) {
    val coalescingKey: SyncWorkCoalescingKey = SyncWorkCoalescingKey(backend, uniqueWorkName)
}

data class SyncWorkRetryPolicy(
    val maxAttempts: Int,
    val backoffPolicy: SyncWorkBackoffPolicy,
    val backoffDelay: Duration,
) {
    init {
        require(maxAttempts > 0) { "Sync work retry maxAttempts must be positive" }
        require(!backoffDelay.isNegative && !backoffDelay.isZero) { "Sync work backoffDelay must be positive" }
    }
}

enum class SyncWorkBackoffPolicy {
    Exponential,
    Linear,
}

data class SyncWorkCoalescingKey(
    val backend: SyncBackendType,
    val uniqueWorkName: String,
)

sealed interface SyncWorkCadence {
    data class Periodic(val interval: Duration) : SyncWorkCadence

    data object OneTime : SyncWorkCadence
}

enum class SyncWorkNetworkRequirement {
    Connected,
    UnmeteredCharging,
}

enum class SyncExistingWorkPolicy {
    Replace,
}

enum class SyncWorkTrigger {
    MANUAL_SYNC,
    REFRESH,
    PERIODIC_AUTO_SYNC,
    CATCH_UP,
}

enum class SyncRefreshSignal {
    NORMAL,
    STRONG_REMOTE_HINT,
}

sealed interface SyncWorkPayload {
    data object StandardRemoteSync : SyncWorkPayload

    data class ProviderParameters(val values: Map<String, String>) : SyncWorkPayload
}

data class RemoteAutoSyncWorkInput(
    val trigger: SyncWorkTrigger,
    val requestedInterval: Duration,
)

class RemoteAutoSyncWorkPolicy(
    private val backend: SyncBackendType,
    private val uniqueWorkName: String,
    private val workPayload: SyncWorkPayload,
    private val retryPolicy: SyncWorkRetryPolicy,
) : SyncWorkPolicy<RemoteAutoSyncWorkInput> {
    override fun plan(input: RemoteAutoSyncWorkInput): SyncWorkDecision =
        SyncWorkDecision(
            scheduledWork =
                listOf(
                    SyncScheduledWork(
                        backend = backend,
                        trigger = input.trigger,
                        uniqueWorkName = uniqueWorkName,
                        cadence = SyncWorkCadence.Periodic(input.requestedInterval),
                        networkRequirement = SyncWorkNetworkRequirement.Connected,
                        existingWorkPolicy = SyncExistingWorkPolicy.Replace,
                        retryPolicy = retryPolicy,
                        payload = workPayload,
                    ),
                ),
        )
}

@Singleton
class WebDavSyncWorkPolicyPlanner
    @Inject
    constructor() {
        private val policy =
            RemoteAutoSyncWorkPolicy(
                backend = SyncBackendType.WEBDAV,
                uniqueWorkName = WebDavSyncWorker.WORK_NAME,
                workPayload = SyncWorkPayload.StandardRemoteSync,
                retryPolicy = REMOTE_AUTO_SYNC_RETRY_POLICY,
            )

        fun planAutoSchedule(interval: String): SyncWorkDecision =
            policy.plan(
                RemoteAutoSyncWorkInput(
                    trigger = SyncWorkTrigger.PERIODIC_AUTO_SYNC,
                    requestedInterval = parseRemoteAutoSyncInterval(interval),
                ),
            )
    }

@Singleton
class GitSyncWorkPolicyPlanner
    @Inject
    constructor() {
        private val policy =
            RemoteAutoSyncWorkPolicy(
                backend = SyncBackendType.GIT,
                uniqueWorkName = GitSyncWorker.WORK_NAME,
                workPayload = SyncWorkPayload.StandardRemoteSync,
                retryPolicy = REMOTE_AUTO_SYNC_RETRY_POLICY,
            )

        fun planAutoSchedule(interval: String): SyncWorkDecision =
            policy.plan(
                RemoteAutoSyncWorkInput(
                    trigger = SyncWorkTrigger.PERIODIC_AUTO_SYNC,
                    requestedInterval = parseRemoteAutoSyncInterval(interval),
                ),
            )
    }

internal val DEFAULT_REMOTE_AUTO_SYNC_INTERVAL: Duration = Duration.ofHours(REMOTE_AUTO_SYNC_HOURS_1)

internal val REMOTE_AUTO_SYNC_RETRY_POLICY =
    SyncWorkRetryPolicy(
        maxAttempts = 3,
        backoffPolicy = SyncWorkBackoffPolicy.Exponential,
        backoffDelay = Duration.ofMinutes(15),
    )

private const val REMOTE_AUTO_SYNC_MINUTES_30 = 30L
private const val REMOTE_AUTO_SYNC_HOURS_1 = 1L
private const val REMOTE_AUTO_SYNC_HOURS_6 = 6L
private const val REMOTE_AUTO_SYNC_HOURS_12 = 12L
private const val REMOTE_AUTO_SYNC_HOURS_24 = 24L

internal fun parseRemoteAutoSyncInterval(interval: String): Duration =
    REMOTE_AUTO_SYNC_INTERVALS[interval] ?: DEFAULT_REMOTE_AUTO_SYNC_INTERVAL

private val REMOTE_AUTO_SYNC_INTERVALS =
    mapOf(
        "30min" to Duration.ofMinutes(REMOTE_AUTO_SYNC_MINUTES_30),
        "1h" to Duration.ofHours(REMOTE_AUTO_SYNC_HOURS_1),
        "6h" to Duration.ofHours(REMOTE_AUTO_SYNC_HOURS_6),
        "12h" to Duration.ofHours(REMOTE_AUTO_SYNC_HOURS_12),
        "24h" to Duration.ofHours(REMOTE_AUTO_SYNC_HOURS_24),
    )
