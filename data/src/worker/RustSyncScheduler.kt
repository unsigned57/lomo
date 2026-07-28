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
import java.util.Locale

/**
 * Post P5-13 single remote-sync enqueue path for WorkManager [RustSyncWorker].
 *
 * Provider-specific workers/schedulers are deleted. Auto-schedule uses the active backend interval
 * from DataStore when auto-sync is enabled. Non-secret backend fields travel in WorkManager input;
 * secrets only as Keystore → process lease ids.
 */
class RustSyncScheduler(
    private val context: Context,
    private val dataStore: LomoDataStore,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val policyPlanner: RustSyncWorkPolicyPlanner = RustSyncWorkPolicyPlanner(),
    private val identityMaterial: SecretMaterialSource? = null,
) {
    suspend fun reschedule() {
        val backend = syncBackendFromPreference(dataStore.syncBackendType.first())
        when (backend) {
            SyncBackendType.NONE,
            SyncBackendType.INBOX,
            -> {
                cancel()
                return
            }
            else -> Unit
        }

        val enabledAuto =
            when (backend) {
                SyncBackendType.GIT ->
                    dataStore.gitSyncEnabled.first() to
                        (dataStore.gitAutoSyncEnabled.first() to dataStore.gitAutoSyncInterval.first())
                SyncBackendType.WEBDAV ->
                    dataStore.webDavSyncEnabled.first() to
                        (dataStore.webDavAutoSyncEnabled.first() to dataStore.webDavAutoSyncInterval.first())
                SyncBackendType.S3 ->
                    dataStore.s3SyncEnabled.first() to
                        (dataStore.s3AutoSyncEnabled.first() to dataStore.s3AutoSyncInterval.first())
                SyncBackendType.NONE,
                SyncBackendType.INBOX,
                -> error("unreachable")
            }
        val enabled = enabledAuto.first
        val autoEnabled = enabledAuto.second.first
        val interval = enabledAuto.second.second
        if (!enabled || !autoEnabled) {
            cancel()
            return
        }

        val root = workspaceRoot.absolutePathOrNull().orEmpty()
        if (root.isBlank()) {
            Timber.w("RustSyncScheduler skip schedule: no Direct workspace root")
            cancel()
            return
        }

        val cycleInput = resolveCycleInput(backend, root) ?: run {
            Timber.w("RustSyncScheduler skip schedule: incomplete backend config backend=%s", backend)
            cancel()
            return
        }

        val workManager = WorkManager.getInstance(context)
        val decision = policyPlanner.planAutoSchedule(interval)
        decision.scheduledWork.forEach { scheduled ->
            workManager.enqueueSyncScheduledWork<RustSyncWorker>(
                scheduledWork = scheduled,
                inputData = cycleInput,
            )
        }
        Timber.d("Rust remote sync scheduled backend=%s interval=%s", backend, interval)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(RustSyncWorker.WORK_NAME)
        Timber.d("Rust remote sync cancelled")
    }

    suspend fun enqueueOneShot(secretFieldKey: String?) {
        val root = workspaceRoot.absolutePathOrNull().orEmpty()
        if (root.isBlank()) {
            Timber.w("RustSyncScheduler one-shot skipped: no Direct workspace root")
            return
        }
        val backend = syncBackendFromPreference(dataStore.syncBackendType.first())
        if (backend == SyncBackendType.NONE || backend == SyncBackendType.INBOX) {
            Timber.w("RustSyncScheduler one-shot skipped: no active remote backend")
            return
        }
        val cycleInput =
            resolveCycleInput(backend, root, secretFieldKeyOverride = secretFieldKey) ?: run {
                Timber.w("RustSyncScheduler one-shot skipped: incomplete backend config")
                return
            }
        val request =
            OneTimeWorkRequestBuilder<RustSyncWorker>()
                .setInputData(cycleInput)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                RustSyncWorker.WORK_NAME + ":oneshot",
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    private suspend fun resolveCycleInput(
        backend: SyncBackendType,
        root: String,
        secretFieldKeyOverride: String? = null,
    ): androidx.work.Data? {
        return when (backend) {
            SyncBackendType.WEBDAV -> {
                val endpoint =
                    dataStore.webDavEndpointUrl.first()?.trim().orEmpty().ifBlank {
                        dataStore.webDavBaseUrl.first()?.trim().orEmpty()
                    }
                val username =
                    identityMaterial
                        ?.readSecretBytes("WEBDAV_USERNAME")
                        ?.toString(Charsets.UTF_8)
                        ?.trim()
                        .orEmpty()
                if (endpoint.isBlank() || username.isBlank()) {
                    return null
                }
                RustSyncWorker.inputData(
                    workspaceRoot = root,
                    backendKind = "webdav",
                    endpointUrl = endpoint,
                    usernameOrAccessKey = username,
                    remoteDatasetId = datasetId("webdav", endpoint, ""),
                    secretFieldKey = secretFieldKeyOverride ?: "WEBDAV_PASSWORD",
                    applyRemote = true,
                )
            }
            SyncBackendType.S3 -> {
                val endpoint = dataStore.s3EndpointUrl.first()?.trim().orEmpty()
                val region = dataStore.s3Region.first()?.trim().orEmpty()
                val bucket = dataStore.s3Bucket.first()?.trim().orEmpty()
                val prefix = dataStore.s3Prefix.first()?.trim().orEmpty()
                val accessKey =
                    identityMaterial
                        ?.readSecretBytes("S3_ACCESS_KEY_ID")
                        ?.toString(Charsets.UTF_8)
                        ?.trim()
                        .orEmpty()
                if (endpoint.isBlank() || region.isBlank() || bucket.isBlank() || accessKey.isBlank()) {
                    return null
                }
                RustSyncWorker.inputData(
                    workspaceRoot = root,
                    backendKind = "s3",
                    endpointUrl = endpoint,
                    usernameOrAccessKey = accessKey,
                    bucket = bucket,
                    prefix = prefix,
                    region = region,
                    remoteDatasetId = datasetId("s3", endpoint, bucket),
                    secretFieldKey = secretFieldKeyOverride ?: "S3_SECRET_ACCESS_KEY",
                    applyRemote = true,
                )
            }
            SyncBackendType.GIT -> {
                // Git composition: native constructs lomo-git (app-private bare mirror) and runs the
                // owner cycle via run_composed_sync_cycle_with_remote_port. Wire field reuse:
                // bucket=branch, prefix=author name, region=author email.
                val remote = dataStore.gitRemoteUrl.first()?.trim().orEmpty()
                if (remote.isBlank()) {
                    return null
                }
                val authorName = dataStore.gitAuthorName.first().trim().ifBlank { "Lomo" }
                val authorEmail =
                    dataStore.gitAuthorEmail
                        .first()
                        .trim()
                        .ifBlank { "git@lomo.local" }
                RustSyncWorker.inputData(
                    workspaceRoot = root,
                    backendKind = "git",
                    endpointUrl = remote,
                    // HTTPS username defaults to "git" inside native when blank + token present.
                    usernameOrAccessKey = "",
                    bucket = "main",
                    prefix = authorName,
                    region = authorEmail,
                    remoteDatasetId = datasetId("git", remote, ""),
                    secretFieldKey = secretFieldKeyOverride ?: "GIT_TOKEN",
                    applyRemote = true,
                )
            }
            SyncBackendType.NONE,
            SyncBackendType.INBOX,
            -> null
        }
    }
}

private const val DATASET_ID_MAX_LEN: Int = 128

private fun datasetId(
    backend: String,
    endpoint: String,
    bucket: String,
): String {
    val raw = "$backend|$endpoint|$bucket"
    return raw.take(DATASET_ID_MAX_LEN).ifBlank { backend }
}

private fun syncBackendFromPreference(value: String): SyncBackendType =
    SyncBackendType.entries.firstOrNull {
        it.name.lowercase(Locale.ROOT) == value.lowercase(Locale.ROOT)
    } ?: SyncBackendType.NONE
