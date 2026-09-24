package com.lomo.data.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.sync.SecretMaterialSource
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.RustSyncWorkPolicyPlanner
import com.lomo.domain.model.SyncBackendType
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Post P5-13 single remote-sync enqueue path for WorkManager [RustSyncWorker].
 *
 * Non-secret backend fields travel in WorkManager input; credential fields travel only as
 * [com.lomo.domain.model.CredentialField] names. Identity and secrets are read at the worker
 * execution boundary. Cycle-input derivation lives in [RustSyncCycleInputFactory] so the
 * config→input rules stay host-testable without WorkManager.
 */
class RustSyncScheduler(
    private val context: Context,
    private val dataStore: LomoDataStore,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val policyPlanner: RustSyncWorkPolicyPlanner = RustSyncWorkPolicyPlanner(),
    identityMaterial: SecretMaterialSource,
) {
    private val inputFactory = RustSyncCycleInputFactory(dataStore, identityMaterial)

    suspend fun reschedule() {
        val plan = resolveAutoSchedulePlan() ?: return
        val workManager = WorkManager.getInstance(context)
        val decision = policyPlanner.planAutoSchedule(plan.interval)
        decision.scheduledWork.forEach { scheduled ->
            workManager.enqueueSyncScheduledWork<RustSyncWorker>(
                scheduledWork = scheduled,
                inputData = plan.cycleInput,
            )
        }
        Timber.d("Rust remote sync scheduled backend=%s interval=%s", plan.backend, plan.interval)
    }

    private suspend fun resolveAutoSchedulePlan(): AutoSchedulePlan? {
        val backend = SyncBackendType.fromStorageValue(dataStore.syncBackendType.first())
        return when (backend) {
            SyncBackendType.NONE,
            SyncBackendType.INBOX,
            -> {
                cancel()
                null
            }
            SyncBackendType.UNKNOWN -> {
                // Unparseable selection: neither schedule nor destroy existing work.
                Timber.w("RustSyncScheduler skip reschedule: unrecognized stored backend")
                null
            }
            else -> remoteSchedulePlan(backend)
        }
    }

    private suspend fun remoteSchedulePlan(backend: SyncBackendType): AutoSchedulePlan? {
        val autoSync =
            when (backend) {
                SyncBackendType.GIT ->
                    dataStore.gitAutoSyncEnabled.first() to dataStore.gitAutoSyncInterval.first()
                SyncBackendType.WEBDAV ->
                    dataStore.webDavAutoSyncEnabled.first() to dataStore.webDavAutoSyncInterval.first()
                SyncBackendType.S3 ->
                    dataStore.s3AutoSyncEnabled.first() to dataStore.s3AutoSyncInterval.first()
                SyncBackendType.NONE,
                SyncBackendType.INBOX,
                SyncBackendType.UNKNOWN,
                -> error("unreachable")
            }
        val autoEnabled = autoSync.first
        val interval = autoSync.second
        if (!autoEnabled) {
            cancel()
            return null
        }

        val root = workspaceRoot.absolutePathOrNull().orEmpty()
        if (root.isBlank()) {
            Timber.w("RustSyncScheduler skip schedule: no Direct workspace root")
            cancel()
            return null
        }

        val cycleInput = inputFactory.resolveCycleInput(backend, root)
        if (cycleInput == null) {
            Timber.w("RustSyncScheduler skip schedule: incomplete backend config backend=%s", backend)
            cancel()
            return null
        }
        return AutoSchedulePlan(backend = backend, interval = interval, cycleInput = cycleInput)
    }

    private data class AutoSchedulePlan(
        val backend: SyncBackendType,
        val interval: String,
        val cycleInput: androidx.work.Data,
    )

    fun cancel() {
        val workManager = WorkManager.getInstance(context)
        RustSyncWorker.cancelTargets().forEach(workManager::cancelUniqueWork)
        Timber.d("Rust remote sync cancelled")
    }

    fun enqueueSaved(input: androidx.work.Data) {
        val request =
            OneTimeWorkRequestBuilder<RustSyncWorker>()
                .setInputData(input)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                RustSyncWorker.DEFERRED_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    /**
     * Enqueues a one-shot Rust sync cycle.
     *
     * The returned outcome only proves WorkManager **acceptance** — completion is owned by the
     * durable cycle record (`cycle_state.rec`), read via
     * [com.lomo.data.engine.sync.RustSyncCycleStatusStore]. Rejections carry the real skip
     * reason instead of silently returning.
     */
    suspend fun enqueueOneShot(secretFieldKey: String?): RustSyncEnqueueOutcome {
        val root = workspaceRoot.absolutePathOrNull().orEmpty()
        if (root.isBlank()) {
            Timber.w("RustSyncScheduler one-shot skipped: no Direct workspace root")
            return RustSyncEnqueueOutcome.Rejected(RustSyncEnqueueRejection.NO_DIRECT_ROOT)
        }
        val backend = SyncBackendType.fromStorageValue(dataStore.syncBackendType.first())
        if (backend == SyncBackendType.NONE ||
            backend == SyncBackendType.INBOX ||
            backend == SyncBackendType.UNKNOWN
        ) {
            Timber.w("RustSyncScheduler one-shot skipped: no active remote backend")
            return RustSyncEnqueueOutcome.Rejected(RustSyncEnqueueRejection.NO_ACTIVE_BACKEND)
        }
        val cycleInput =
            inputFactory.resolveCycleInput(backend, root, secretFieldKeyOverride = secretFieldKey) ?: run {
                Timber.w("RustSyncScheduler one-shot skipped: incomplete backend config")
                return RustSyncEnqueueOutcome.Rejected(RustSyncEnqueueRejection.INCOMPLETE_CONFIG)
            }
        val request =
            OneTimeWorkRequestBuilder<RustSyncWorker>()
                .setInputData(cycleInput)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                RustSyncWorker.ONESHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        return RustSyncEnqueueOutcome.Accepted(RustSyncWorker.ONESHOT_WORK_NAME)
    }
}

/**
 * WorkManager enqueue outcome — acceptance only, never cycle completion.
 *
 * [Accepted.workName] is the unique-work name observers can correlate with the durable record.
 */
sealed interface RustSyncEnqueueOutcome {
    data class Accepted(
        val workName: String,
    ) : RustSyncEnqueueOutcome

    data class Rejected(
        val reason: RustSyncEnqueueRejection,
    ) : RustSyncEnqueueOutcome
}

/** Real skip reasons for a rejected one-shot enqueue (no silent returns). */
enum class RustSyncEnqueueRejection {
    NO_DIRECT_ROOT,
    NO_ACTIVE_BACKEND,
    INCOMPLETE_CONFIG,
}
